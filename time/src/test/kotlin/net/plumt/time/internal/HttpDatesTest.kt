package net.plumt.time.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.random.Random

class HttpDatesTest {

    private fun epoch(text: String) = Instant.parse(text).toEpochMilli()

    @Test
    fun `IMF-fixdate`() {
        assertEquals(epoch("1994-11-06T08:49:37Z"), HttpDates.parse("Sun, 06 Nov 1994 08:49:37 GMT"))
        assertEquals(epoch("2026-11-03T12:00:00Z"), HttpDates.parse("Tue, 03 Nov 2026 12:00:00 GMT"))
    }

    @Test
    fun `RFC 850 — 두 자리 연도는 70 미만이면 2000년대`() {
        assertEquals(epoch("1994-11-06T08:49:37Z"), HttpDates.parse("Sunday, 06-Nov-94 08:49:37 GMT"))
        assertEquals(epoch("2026-11-03T12:00:00Z"), HttpDates.parse("Tuesday, 03-Nov-26 12:00:00 GMT"))
        assertEquals(epoch("1970-01-01T00:00:00Z"), HttpDates.parse("Thursday, 01-Jan-70 00:00:00 GMT"))
        assertEquals(epoch("2069-12-31T23:59:59Z"), HttpDates.parse("Tuesday, 31-Dec-69 23:59:59 GMT"))
    }

    @Test
    fun `asctime — 한 자리 날은 공백으로 채운다`() {
        assertEquals(epoch("1994-11-06T08:49:37Z"), HttpDates.parse("Sun Nov  6 08:49:37 1994"))
        assertEquals(epoch("2026-11-03T12:00:00Z"), HttpDates.parse("Tue Nov  3 12:00:00 2026"))
        assertEquals(epoch("2026-11-30T12:00:00Z"), HttpDates.parse("Mon Nov 30 12:00:00 2026"))
    }

    @Test
    fun `앞뒤 공백은 지우고 윤초 60 은 다음 분으로 읽는다`() {
        assertEquals(epoch("2026-11-03T12:00:00Z"), HttpDates.parse("  Tue, 03 Nov 2026 12:00:00 GMT \t"))
        assertEquals(epoch("2016-12-31T23:59:59Z") + 1_000, HttpDates.parse("Sat, 31 Dec 2016 23:59:60 GMT"))
    }

    @Test
    fun `윤년 2월 29일`() {
        assertEquals(epoch("2024-02-29T00:00:00Z"), HttpDates.parse("Thu, 29 Feb 2024 00:00:00 GMT"))
        assertEquals(epoch("2000-02-29T00:00:00Z"), HttpDates.parse("Tue, 29 Feb 2000 00:00:00 GMT"))
        assertNull(HttpDates.parse("Thu, 29 Feb 2025 00:00:00 GMT"))
        assertNull(HttpDates.parse("Thu, 29 Feb 1900 00:00:00 GMT"))
    }

    @Test
    fun `틀린 값은 null`() {
        listOf(
            "",
            "   ",
            "garbage",
            "Mon, 03 Nov 2026 12:00:00 GMT", // 요일 불일치
            "Tue, 03 Nov 2026 12:00:00 UTC", // GMT 만
            "Tue, 03 Nov 2026 12:00:00 gmt",
            "tue, 03 Nov 2026 12:00:00 GMT",
            "Tue, 3 Nov 2026 12:00:00 GMT", // 두 자리 날
            "Tue, 03 nov 2026 12:00:00 GMT",
            "Tue, 03 Nov 2026 24:00:00 GMT",
            "Tue, 03 Nov 2026 12:60:00 GMT",
            "Tue, 03 Nov 2026 12:00:61 GMT",
            "Tue, 00 Nov 2026 12:00:00 GMT",
            "Tue, 31 Nov 2026 12:00:00 GMT",
            "Tue, 03 Nov 2026 12:00:00 GMT extra",
            "Tue, 03 Nov 2026 12:00 GMT",
            "Tue, 03 Nov 26 12:00:00 GMT",
            "2026-11-03T12:00:00Z",
            "Tue Nov 3 12:00:00 2026", // asctime 은 두 칸
            "Tue Nov 03 12:00:00 2026",
            "Tue, 03 Nov 2026 12:00:00 GMT" + " ".repeat(10) + "x",
            "Tue, 03 Nov 2026 12:00:00 GMT".padStart(80, 'x'),
            "Tue, ０３ Nov 2026 12:00:00 GMT", // 전각 숫자
        ).forEach { assertNull("'$it'", HttpDates.parse(it)) }
    }

    @Test
    fun `days_from_civil 은 java time 과 같다(무작위 1만 날짜)`() {
        val random = Random(42)
        repeat(10_000) {
            val day = LocalDate.ofEpochDay(random.nextLong(-1_000_000, 1_000_000))
            assertEquals(day.toString(), day.toEpochDay(), HttpDates.daysFromCivil(day.year, day.monthValue, day.dayOfMonth))
        }
    }

    @Test
    fun `java time 으로 만든 IMF-fixdate 를 그대로 되읽는다(무작위 2천 시각)`() {
        val format = DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US).withZone(ZoneOffset.UTC)
        val random = Random(7)
        repeat(2_000) {
            val second = random.nextLong(0, 7_258_118_400L)
            val text = format.format(Instant.ofEpochSecond(second))
            assertEquals(text, second * 1000, HttpDates.parse(text))
        }
    }

    @Test
    fun `무작위 문자열은 예외 없이 null 이나 값을 낸다`() {
        val random = Random(99)
        val alphabet = "SunMoTeWdhFriaJbpyAgOcNvD ,:-0123456789GMT\t\u0000한"
        repeat(20_000) {
            val text = String(CharArray(random.nextInt(0, 40)) { alphabet[random.nextInt(alphabet.length)] })
            HttpDates.parse(text)
        }
    }
}
