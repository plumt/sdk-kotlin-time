package net.plumt.time.internal

import net.plumt.time.FailureReason
import net.plumt.time.TimeOrigin
import net.plumt.time.TimeQuery
import net.plumt.time.TimeSample
import java.net.HttpURLConnection
import java.net.URL

/**
 * HTTP `Date` 헤더 출처.
 *
 * 연결(TCP·TLS)을 먼저 맺고, 요청을 보내 응답 헤더를 받기까지를 왕복으로 잰다. 서버는 그 사이 어딘가에서
 * `Date` 를 찍고 초 미만을 버리므로, 응답 직후 순간의 서버 시각을 `Date + 0.5초 + 왕복/2` 로 잡고
 * 오차를 `0.5초 + 왕복/2` 로 둔다.
 */
internal class HttpDateTimeSource(
    override val name: String,
    private val url: URL,
    private val open: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection },
) : SampleSource() {

    override val origin: TimeOrigin = TimeOrigin.HTTP_DATE

    override fun sample(request: TimeQuery): TimeSample {
        val connection = open(url).apply {
            requestMethod = "HEAD"
            connectTimeout = request.timeoutMillis
            readTimeout = request.timeoutMillis
            useCaches = false
            instanceFollowRedirects = false
            // 중간 캐시가 저장해 둔 과거 응답의 Date 를 읽지 않도록 한다
            setRequestProperty("Cache-Control", "no-cache")
            setRequestProperty("Pragma", "no-cache")
        }
        try {
            val clock = request.monotonicClock
            connection.connect()
            val sentAt = clock.elapsedMillis()
            connection.responseCode
            val receivedAt = clock.elapsedMillis()
            val header = connection.getHeaderField("Date")
                ?: throw SyncError(FailureReason.HTTP_DATE_MISSING, "$name: 응답에 Date 헤더가 없다", null, name)
            val date = HttpDates.parse(header)
                ?: throw SyncError(
                    FailureReason.HTTP_DATE_INVALID,
                    "$name: Date 헤더를 읽지 못했다: '${header.take(MAX_ECHO)}'",
                    null,
                    name,
                )
            val roundTrip = receivedAt - sentAt
            val halfTrip = (roundTrip + 1) / 2
            return TimeSample(
                serverEpochMillis = date + HALF_SECOND + roundTrip / 2,
                anchorElapsedMillis = receivedAt,
                roundTripMillis = roundTrip,
                uncertaintyMillis = HALF_SECOND + halfTrip + 1,
            )
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        const val HALF_SECOND = 500L
        const val MAX_ECHO = 64
    }
}

/**
 * HTTP 날짜 파서(RFC 9110 §5.6.7). 받는 쪽이 읽어야 하는 세 형식을 모두 읽는다.
 * `java.time` 을 쓰지 않아 API 26 미만 Android 에서도 desugaring 없이 돈다.
 */
// 달력 공식(H. Hinnant)의 고정 수치는 공식 그대로 둔다
@Suppress("MagicNumber")
internal object HttpDates {
    private val IMF_FIXDATE = Regex(
        "^(Mon|Tue|Wed|Thu|Fri|Sat|Sun), ([0-9]{2}) (Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec) ([0-9]{4}) ([0-9]{2}):([0-9]{2}):([0-9]{2}) GMT$",
    )
    private val RFC_850 = Regex(
        "^(Monday|Tuesday|Wednesday|Thursday|Friday|Saturday|Sunday), ([0-9]{2})-(Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)-([0-9]{2}) ([0-9]{2}):([0-9]{2}):([0-9]{2}) GMT$",
    )
    private val ASCTIME = Regex(
        "^(Mon|Tue|Wed|Thu|Fri|Sat|Sun) (Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec) ( [1-9]|[1-3][0-9]) ([0-9]{2}):([0-9]{2}):([0-9]{2}) ([0-9]{4})$",
    )
    private val MONTHS = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
    private val DAYS = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")

    private const val MAX_INPUT = 64
    private const val TWO_DIGIT_PIVOT = 70
    private const val CENTURY_1900 = 1900
    private const val CENTURY_2000 = 2000
    private const val MAX_HOUR = 23
    private const val MAX_MINUTE = 59
    private const val MAX_SECOND = 60 // 윤초
    private const val SECONDS_PER_DAY = 86_400L
    private const val SECONDS_PER_HOUR = 3_600L
    private const val SECONDS_PER_MINUTE = 60L
    private const val MILLIS_PER_SECOND = 1_000L

    /** 읽으면 epoch 밀리초, 형식·날짜가 틀리면 `null`. */
    fun parse(value: String): Long? {
        val text = value.trim()
        if (text.length > MAX_INPUT) return null
        IMF_FIXDATE.matchEntire(text)?.let { m ->
            val g = m.groupValues
            return build(g[1], g[4].toInt(), g[3], g[2].toInt(), g[5].toInt(), g[6].toInt(), g[7].toInt())
        }
        RFC_850.matchEntire(text)?.let { m ->
            val g = m.groupValues
            val yy = g[4].toInt()
            val year = if (yy < TWO_DIGIT_PIVOT) CENTURY_2000 + yy else CENTURY_1900 + yy
            return build(g[1].take(3), year, g[3], g[2].toInt(), g[5].toInt(), g[6].toInt(), g[7].toInt())
        }
        ASCTIME.matchEntire(text)?.let { m ->
            val g = m.groupValues
            return build(g[1], g[7].toInt(), g[2], g[3].trim().toInt(), g[4].toInt(), g[5].toInt(), g[6].toInt())
        }
        return null
    }

    @Suppress("LongParameterList")
    private fun build(dayName: String, year: Int, monthName: String, day: Int, hour: Int, minute: Int, second: Int): Long? {
        val month = MONTHS.indexOf(monthName) + 1
        if (day !in 1..daysInMonth(year, month)) return null
        if (hour > MAX_HOUR || minute > MAX_MINUTE || second > MAX_SECOND) return null
        val days = daysFromCivil(year, month, day)
        // 1970-01-01 은 목요일(월요일 0 기준 3)
        if (DAYS[Math.floorMod(days + 3, 7L).toInt()] != dayName) return null
        val seconds = days * SECONDS_PER_DAY + hour * SECONDS_PER_HOUR + minute * SECONDS_PER_MINUTE + second
        return seconds * MILLIS_PER_SECOND
    }

    private fun daysInMonth(year: Int, month: Int): Int = when (month) {
        2 -> if (isLeap(year)) 29 else 28
        4, 6, 9, 11 -> 30
        else -> 31
    }

    private fun isLeap(year: Int): Boolean = year % 4 == 0 && (year % 100 != 0 || year % 400 == 0)

    /** 그레고리력 날짜 → 1970-01-01 부터의 일수(H. Hinnant 의 days_from_civil). */
    fun daysFromCivil(year: Int, month: Int, day: Int): Long {
        val y = (if (month <= 2) year - 1 else year).toLong()
        val era = Math.floorDiv(y, 400L)
        val yoe = y - era * 400
        val mp = (month + 9) % 12
        val doy = (153 * mp + 2) / 5 + day - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        return era * 146_097 + doe - 719_468
    }
}
