package net.plumt.time.internal

import net.plumt.time.FailureReason
import net.plumt.time.TimeSample
import net.plumt.time.TimeSyncException

/**
 * SNTP 패킷(RFC 5905 48바이트 헤더) 만들기·읽기. 소켓을 모르는 순수 함수라 바이트 배열만으로 시험한다.
 *
 * 기기 벽시계를 쓰지 않는다. 요청의 전송 시각 자리에는 난수(nonce)를 넣고 응답의 원본 시각이 그 값인지만 본다.
 * 서버 시각은 응답 직후 단조 시계 순간(T4)에 대해 `T3 + 지연/2` 로 구한다 — 지연 = 왕복(단조) − 서버 처리(T3 − T2).
 * 그래서 조회 도중 사용자가 기기 시계를 바꿔도 결과가 흔들리지 않는다.
 */
// 비트 폭·필드 오프셋 같은 프로토콜 고정 수치가 많아 상수 이름이 오히려 읽기를 해친다
@Suppress("MagicNumber")
internal object Ntp {
    const val PACKET_SIZE = 48
    private const val CLIENT_FIRST_BYTE = 0x23 // LI 0 · 버전 4 · 모드 3(클라이언트)
    private const val MODE_SERVER = 4
    private const val LI_ALARM = 3
    private const val MAX_STRATUM = 15
    private const val ROOT_DELAY_OFFSET = 4
    private const val ROOT_DISPERSION_OFFSET = 8
    private const val REFERENCE_ID_OFFSET = 12
    private const val ORIGINATE_OFFSET = 24
    private const val RECEIVE_OFFSET = 32
    private const val TRANSMIT_OFFSET = 40

    /** 1900-01-01 과 1970-01-01 사이의 초(70년 + 윤일 17일). */
    const val NTP_EPOCH_OFFSET_SECONDS = 2_208_988_800L
    private const val ERA_SECONDS = 1L shl 32
    private const val ERA_PIVOT_BIT = 0x8000_0000L
    private const val HALF_FRACTION = 1L shl 31

    /** 루트 거리(rootDelay/2 + rootDispersion) 상한. RFC 5905 의 MAXDIST 1.5초. */
    const val MAX_ROOT_DISTANCE_MILLIS = 1_500L

    private const val MILLIS_PER_SECOND = 1_000L
    private const val BYTE_MASK = 0xFF
    private const val UINT32_MASK = 0xFFFF_FFFFL
    private const val SHORT_FORMAT_DIVISOR = 65_536.0

    fun request(nonce: Long): ByteArray {
        val buffer = ByteArray(PACKET_SIZE)
        buffer[0] = CLIENT_FIRST_BYTE.toByte()
        writeLong(buffer, TRANSMIT_OFFSET, nonce)
        return buffer
    }

    /**
     * 응답을 검사해 [TimeSample] 로 바꾼다.
     *
     * @param roundTripMillis 요청 직전 ~ 응답 직후 단조 시계 차이
     * @param anchorElapsedMillis 응답 직후 단조 시계 값
     * @throws TimeSyncException 응답이 틀렸을 때(원인별)
     */
    fun parse(
        source: String,
        packet: ByteArray,
        length: Int,
        nonce: Long,
        roundTripMillis: Long,
        anchorElapsedMillis: Long,
    ): TimeSample {
        fun fail(reason: FailureReason, message: String): Nothing =
            throw TimeSyncException(reason, "$source: $message", null, source)

        if (length < PACKET_SIZE) fail(FailureReason.INVALID_RESPONSE, "응답이 ${PACKET_SIZE}바이트보다 짧다: $length")
        val first = packet[0].toInt() and BYTE_MASK
        val leap = first ushr 6
        val version = (first ushr 3) and 0b111
        val mode = first and 0b111
        if (mode != MODE_SERVER) fail(FailureReason.INVALID_RESPONSE, "서버 응답 모드(4)가 아니다: $mode")
        if (version !in 1..4) fail(FailureReason.INVALID_RESPONSE, "모르는 NTP 버전: $version")
        if (readLong(packet, ORIGINATE_OFFSET) != nonce) {
            fail(FailureReason.INVALID_RESPONSE, "원본 시각이 요청과 다르다 — 다른 요청의 응답이거나 위조")
        }
        val stratum = packet[1].toInt() and BYTE_MASK
        if (stratum == 0) {
            val code = kissCode(packet)
            if (code != null) fail(FailureReason.KISS_OF_DEATH, "Kiss-o'-Death $code")
            fail(FailureReason.INVALID_STRATUM, "stratum 0(코드 없음)")
        }
        if (stratum > MAX_STRATUM) fail(FailureReason.INVALID_STRATUM, "stratum $stratum — 동기화되지 않은 서버")
        if (leap == LI_ALARM) fail(FailureReason.SERVER_UNSYNCHRONIZED, "LI 3 — 서버 시계가 동기화돼 있지 않다")

        if (readLong(packet, RECEIVE_OFFSET) == 0L || readLong(packet, TRANSMIT_OFFSET) == 0L) {
            fail(FailureReason.INVALID_RESPONSE, "수신·전송 시각이 비었다")
        }
        val receive = timestampMillis(packet, RECEIVE_OFFSET)
        val transmit = timestampMillis(packet, TRANSMIT_OFFSET)
        val serverProcessing = transmit - receive
        if (serverProcessing < 0) fail(FailureReason.INVALID_RESPONSE, "전송 시각이 수신 시각보다 앞선다(${serverProcessing}ms)")

        val rootDistance = shortFormatMillis(packet, ROOT_DELAY_OFFSET) / 2 + shortFormatMillis(packet, ROOT_DISPERSION_OFFSET)
        if (rootDistance > MAX_ROOT_DISTANCE_MILLIS) {
            fail(FailureReason.INVALID_RESPONSE, "루트 거리 ${rootDistance}ms 가 상한 ${MAX_ROOT_DISTANCE_MILLIS}ms 를 넘는다")
        }

        val delay = (roundTripMillis - serverProcessing).coerceAtLeast(0)
        return TimeSample(
            serverEpochMillis = transmit + delay / 2,
            anchorElapsedMillis = anchorElapsedMillis,
            roundTripMillis = roundTripMillis,
            // 편도 비대칭 최대치(지연/2) + 서버가 밝힌 기준시까지의 거리 + 밀리초 단위 절삭
            uncertaintyMillis = (delay + 1) / 2 + rootDistance + 1,
        )
    }

    /** stratum 0 응답의 참조 ID 가 대문자·숫자 4자(뒤 NUL 허용)면 KoD 코드. */
    private fun kissCode(packet: ByteArray): String? {
        val chars = (0 until 4).map { packet[REFERENCE_ID_OFFSET + it].toInt() and BYTE_MASK }
        val text = chars.takeWhile { it != 0 }
        if (text.isEmpty() || chars.drop(text.size).any { it != 0 }) return null
        if (!text.all { it in 'A'.code..'Z'.code || it in '0'.code..'9'.code }) return null
        return text.joinToString("") { it.toChar().toString() }
    }

    /**
     * 64비트 NTP 타임스탬프 → epoch 밀리초. 초 필드의 최상위 비트가 0 이면 2036-02-07 이후(era 1)로 본다 —
     * 1968 ~ 2104 를 한 번에 다룬다.
     */
    fun timestampMillis(packet: ByteArray, offset: Int): Long {
        var seconds = readUInt32(packet, offset)
        val fraction = readUInt32(packet, offset + 4)
        if (seconds and ERA_PIVOT_BIT == 0L) seconds += ERA_SECONDS
        // 소수부는 가장 가까운 밀리초로 반올림한다(내림이면 평균 0.5ms 늦다)
        return (seconds - NTP_EPOCH_OFFSET_SECONDS) * MILLIS_PER_SECOND + ((fraction * MILLIS_PER_SECOND + HALF_FRACTION) ushr 32)
    }

    /** NTP 짧은 형식(16.16 고정소수점) → 밀리초(올림). */
    private fun shortFormatMillis(packet: ByteArray, offset: Int): Long =
        kotlin.math.ceil(readUInt32(packet, offset) * MILLIS_PER_SECOND / SHORT_FORMAT_DIVISOR).toLong()

    private fun readUInt32(buffer: ByteArray, offset: Int): Long {
        var value = 0L
        for (i in 0 until 4) value = (value shl 8) or (buffer[offset + i].toLong() and BYTE_MASK.toLong())
        return value and UINT32_MASK
    }

    private fun readLong(buffer: ByteArray, offset: Int): Long = (readUInt32(buffer, offset) shl 32) or readUInt32(buffer, offset + 4)

    private fun writeLong(buffer: ByteArray, offset: Int, value: Long) {
        for (i in 0 until 8) buffer[offset + i] = (value ushr (56 - 8 * i)).toByte()
    }
}
