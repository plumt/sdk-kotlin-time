package net.plumt.time.internal

import net.plumt.time.FailureReason
import net.plumt.time.NtpReply
import net.plumt.time.SERVER_TIME
import net.plumt.time.assertThrows
import net.plumt.time.writeNtpTimestamp
import net.plumt.time.writeUInt32
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import kotlin.random.Random

class NtpPacketTest {

    private val nonce = 0x0123_4567_89AB_CDEFL
    private val request = Ntp.request(nonce)

    private fun parse(reply: NtpReply, roundTrip: Long = 40, anchor: Long = 5_000): net.plumt.time.TimeSample {
        val bytes = reply.build(request)
        return Ntp.parse("srv", bytes, bytes.size, nonce, roundTrip, anchor)
    }

    private fun failure(reply: NtpReply, roundTrip: Long = 40): SyncError =
        assertThrows { parse(reply, roundTrip) }

    @Test
    fun `요청은 48바이트 LI 0 버전 4 모드 3 이고 전송 시각 자리에 난수를 담는다`() {
        assertEquals(48, request.size)
        assertEquals(0x23, request[0].toInt())
        assertTrue((1 until 40).all { request[it] == 0.toByte() })
        var echoed = 0L
        for (i in 40 until 48) echoed = (echoed shl 8) or (request[i].toLong() and 0xFF)
        assertEquals(nonce, echoed)
    }

    @Test
    fun `정상 응답 — 서버 시각은 T3 더하기 지연의 절반이고 기기 시계를 쓰지 않는다`() {
        val sample = parse(NtpReply(receive = SERVER_TIME, transmit = SERVER_TIME + 10), roundTrip = 50, anchor = 7_000)
        // 지연 = 50 − 10 = 40, 서버 시각 = T3 + 20
        assertEquals(SERVER_TIME + 10 + 20, sample.serverEpochMillis)
        assertEquals(7_000, sample.anchorElapsedMillis)
        assertEquals(50, sample.roundTripMillis)
        assertEquals(21L, sample.uncertaintyMillis)
    }

    @Test
    fun `오차 추정은 지연 절반과 루트 거리를 더한다`() {
        // rootDelay 0.5초(0x8000), rootDispersion 0.25초(0x4000) → 루트 거리 250 + 250 = 500
        val sample = parse(NtpReply(rootDelayRaw = 0x0000_8000L, rootDispersionRaw = 0x0000_4000L), roundTrip = 41)
        assertEquals((41 + 1) / 2 + 500 + 1L, sample.uncertaintyMillis)
    }

    @Test
    fun `왕복보다 서버 처리가 길면 지연을 0 으로 본다`() {
        val sample = parse(NtpReply(receive = SERVER_TIME, transmit = SERVER_TIME + 100), roundTrip = 30)
        assertEquals(SERVER_TIME + 100, sample.serverEpochMillis)
        assertEquals(1L, sample.uncertaintyMillis)
    }

    @Test
    fun `48바이트보다 긴 응답(확장 필드)도 앞 48바이트로 읽는다`() {
        val sample = parse(NtpReply(length = 68))
        assertEquals(SERVER_TIME + 20, sample.serverEpochMillis)
    }

    @Test
    fun `짧은 응답은 INVALID_RESPONSE`() {
        val e = failure(NtpReply(length = 47))
        assertEquals(FailureReason.INVALID_RESPONSE, e.reason)
        assertEquals("srv", e.source)
        assertTrue(e.message!!.contains("47"))
    }

    @Test
    fun `서버 모드가 아니면 INVALID_RESPONSE`() {
        listOf(0, 1, 2, 3, 5, 6, 7).forEach { mode ->
            assertEquals("모드 $mode", FailureReason.INVALID_RESPONSE, failure(NtpReply(mode = mode)).reason)
        }
    }

    @Test
    fun `버전은 1 에서 4 만 받는다`() {
        assertEquals(FailureReason.INVALID_RESPONSE, failure(NtpReply(version = 0)).reason)
        assertEquals(FailureReason.INVALID_RESPONSE, failure(NtpReply(version = 5)).reason)
        (1..4).forEach { parse(NtpReply(version = it)) }
    }

    @Test
    fun `원본 시각이 요청의 난수와 다르면 위조로 보고 INVALID_RESPONSE`() {
        val e = failure(NtpReply(originateOverride = ByteArray(8) { 1 }))
        assertEquals(FailureReason.INVALID_RESPONSE, e.reason)
        assertTrue(e.message!!.contains("원본 시각"))
    }

    @Test
    fun `stratum 0 과 대문자 코드는 KISS_OF_DEATH`() {
        listOf("RATE", "DENY", "RSTR", "INIT").forEach { code ->
            val e = failure(NtpReply(stratum = 0, referenceId = code.toByteArray(Charsets.US_ASCII)))
            assertEquals(FailureReason.KISS_OF_DEATH, e.reason)
            assertTrue(e.message!!.contains(code))
        }
        // 뒤 NUL 은 허용
        val short = failure(NtpReply(stratum = 0, referenceId = byteArrayOf('N'.code.toByte(), 'O'.code.toByte(), 0, 0)))
        assertEquals(FailureReason.KISS_OF_DEATH, short.reason)
    }

    @Test
    fun `stratum 0 인데 코드가 아니면 INVALID_STRATUM`() {
        listOf(
            byteArrayOf(0, 0, 0, 0),
            "rate".toByteArray(),
            byteArrayOf('A'.code.toByte(), 0, 'B'.code.toByte(), 0),
            byteArrayOf(0xC0.toByte(), 0xA8.toByte(), 1, 1),
        ).forEach { id ->
            assertEquals(FailureReason.INVALID_STRATUM, failure(NtpReply(stratum = 0, referenceId = id)).reason)
        }
    }

    @Test
    fun `stratum 16 이상은 INVALID_STRATUM 이고 1 에서 15 는 받는다`() {
        assertEquals(FailureReason.INVALID_STRATUM, failure(NtpReply(stratum = 16)).reason)
        assertEquals(FailureReason.INVALID_STRATUM, failure(NtpReply(stratum = 255)).reason)
        listOf(1, 15).forEach { parse(NtpReply(stratum = it)) }
    }

    @Test
    fun `LI 3 은 SERVER_UNSYNCHRONIZED 이고 LI 1 2 는 받는다`() {
        assertEquals(FailureReason.SERVER_UNSYNCHRONIZED, failure(NtpReply(leap = 3)).reason)
        parse(NtpReply(leap = 1))
        parse(NtpReply(leap = 2))
    }

    @Test
    fun `빈 수신·전송 시각은 INVALID_RESPONSE`() {
        assertEquals(FailureReason.INVALID_RESPONSE, failure(NtpReply(transmit = null)).reason)
        assertEquals(FailureReason.INVALID_RESPONSE, failure(NtpReply(receive = null)).reason)
    }

    @Test
    fun `전송 시각이 수신 시각보다 앞서면 INVALID_RESPONSE`() {
        val e = failure(NtpReply(receive = SERVER_TIME + 1, transmit = SERVER_TIME))
        assertEquals(FailureReason.INVALID_RESPONSE, e.reason)
    }

    @Test
    fun `루트 거리가 1500ms 를 넘으면 INVALID_RESPONSE 이고 정확히 1500 은 받는다`() {
        // dispersion 1.5초 = 0x0001_8000
        parse(NtpReply(rootDispersionRaw = 0x0001_8000L))
        val e = failure(NtpReply(rootDispersionRaw = 0x0001_8001L))
        assertEquals(FailureReason.INVALID_RESPONSE, e.reason)
        assertTrue(e.message!!.contains("루트 거리"))
    }

    @Test
    fun `타임스탬프 변환 — 소수부와 2036 era 전환`() {
        val buffer = ByteArray(8)
        writeNtpTimestamp(buffer, 0, SERVER_TIME + 999)
        assertEquals(SERVER_TIME + 999, Ntp.timestampMillis(buffer, 0))

        // 초 필드 0 은 era 1 의 시작 = 2036-02-07T06:28:16Z
        writeUInt32(buffer, 0, 0)
        writeUInt32(buffer, 4, 0)
        assertEquals(Instant.parse("2036-02-07T06:28:16Z").toEpochMilli(), Ntp.timestampMillis(buffer, 0))

        // 최상위 비트가 선 마지막 초 = era 0 의 끝 근처(2036 이전)
        writeUInt32(buffer, 0, 0xFFFF_FFFFL)
        assertEquals(Instant.parse("2036-02-07T06:28:15Z").toEpochMilli(), Ntp.timestampMillis(buffer, 0))

        writeNtpTimestamp(buffer, 0, Instant.parse("2040-01-01T00:00:00.500Z").toEpochMilli())
        assertEquals(Instant.parse("2040-01-01T00:00:00.500Z").toEpochMilli(), Ntp.timestampMillis(buffer, 0))
    }

    @Test
    fun `무작위 패킷은 SyncError 이나 결과만 낸다(크래시 없음)`() {
        val random = Random(20261103)
        var parsed = 0
        repeat(20_000) {
            val size = random.nextInt(0, 80)
            val bytes = random.nextBytes(size)
            // 절반은 원본 시각을 맞춰 깊은 분기까지 들어가게 한다
            if (size >= 48 && random.nextBoolean()) {
                request.copyOfRange(40, 48).copyInto(bytes, 24)
                bytes[0] = ((random.nextInt(0, 3) shl 6) or (4 shl 3) or 4).toByte()
                bytes[1] = random.nextInt(1, 16).toByte()
                for (i in 4 until 12) bytes[i] = 0
            }
            try {
                Ntp.parse("fuzz", bytes, size, nonce, random.nextLong(0, 2_000), 1)
                parsed++
            } catch (_: SyncError) {
                // 원인 있는 실패만 허용한다
            }
        }
        assertTrue("깊은 분기까지 들어간 정상 결과가 있어야 한다: $parsed", parsed > 0)
    }
}
