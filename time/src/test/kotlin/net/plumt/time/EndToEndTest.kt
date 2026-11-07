package net.plumt.time

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket

/** 가짜 NTP·HTTP 서버를 실제 소켓으로 엮어 폴백 사슬 전체를 본다. */
class EndToEndTest {

    private val date = "Date: Tue, 03 Nov 2026 12:00:00 GMT"

    private fun clock(vararg sources: TimeSource, policy: FallbackPolicy = FallbackPolicy.DEVICE_CLOCK, wall: FakeWall = FakeWall()) =
        TrustedClock.builder().sources(sources.toList()).timeoutMillis(300).fallbackPolicy(policy)
            .wallClock(wall).dispatcher(Dispatchers.IO).build()

    @Test
    fun `NTP 성공`() = runBlocking {
        FakeNtpServer { NtpReply().build(it) }.use { ntp ->
            val reading = clock(TimeSources.ntp("127.0.0.1", ntp.port)).now()
            assertEquals(TimeOrigin.NTP, reading.origin)
            assertTrue(reading.epochMillis - SERVER_TIME in 0..1_000)
            assertTrue(reading.uncertaintyMillis!! < 1_000)
        }
    }

    @Test
    fun `NTP 시간 초과 → HTTP Date`() = runBlocking {
        FakeNtpServer { null }.use { ntp ->
            FakeHttpServer { FakeHttpServer.response(date) }.use { http ->
                val reading = clock(TimeSources.ntp("127.0.0.1", ntp.port), TimeSources.httpDate(http.url, true)).now()
                assertEquals(TimeOrigin.HTTP_DATE, reading.origin)
                assertEquals(http.url, reading.sourceName)
                assertTrue(reading.epochMillis - SERVER_TIME in 500..2_000)
                assertTrue(reading.uncertaintyMillis!! >= 501)
            }
        }
    }

    @Test
    fun `NTP KoD·HTTP Date 없음 → 기기 시계, 시도별 원인이 남는다`() = runBlocking {
        FakeNtpServer { NtpReply(stratum = 0, referenceId = "DENY".toByteArray()).build(it) }.use { ntp ->
            FakeHttpServer { FakeHttpServer.response("Server: x") }.use { http ->
                val wall = FakeWall(now = 42_000)
                val reading = clock(TimeSources.ntp("127.0.0.1", ntp.port), TimeSources.httpDate(http.url, true), wall = wall).now()
                assertTrue(reading.isDeviceFallback)
                assertEquals(42_000L, reading.epochMillis)
                val failure = reading.failure!!
                assertEquals(FailureReason.ALL_SOURCES_FAILED, failure.reason)
                assertEquals(listOf(FailureReason.KISS_OF_DEATH, FailureReason.HTTP_DATE_MISSING), failure.failures.map { it.reason })
            }
        }
    }

    @Test
    fun `모든 서버 응답 없음 — 정책 NONE 은 TIMEOUT 을 던진다`() = runBlocking {
        FakeNtpServer { null }.use { a ->
            FakeNtpServer { null }.use { b ->
                val e = assertThrowsSuspend<TimeSyncException> {
                    clock(TimeSources.ntp("127.0.0.1", a.port), TimeSources.ntp("127.0.0.1", b.port), policy = FallbackPolicy.NONE).now()
                }
                assertEquals(FailureReason.TIMEOUT, e.reason)
                assertEquals(2, e.failures.size)
            }
        }
    }

    @Test
    fun `HTTP 연결 거부·Date 파싱 실패 원인`() = runBlocking {
        val closed = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort }
        FakeHttpServer { FakeHttpServer.response("Date: soon") }.use { bad ->
            val e = assertThrowsSuspend<TimeSyncException> {
                clock(TimeSources.httpDate("http://127.0.0.1:$closed/", true), TimeSources.httpDate(bad.url, true)).sync()
            }
            assertEquals(listOf(FailureReason.CONNECTION_FAILED, FailureReason.HTTP_DATE_INVALID), e.failures.map { it.reason })
            assertTrue(e.failures[0].cause is java.net.ConnectException)
        }
    }

    @Test
    fun `호스트 이름 조회 실패는 HOST_NOT_FOUND`() = runBlocking {
        val source = net.plumt.time.internal.NtpTimeSource("nowhere.invalid", 123, resolver = { throw java.net.UnknownHostException(it) })
        val e = assertThrowsSuspend<TimeSyncException> { clock(source).sync() }
        assertEquals(FailureReason.HOST_NOT_FOUND, e.reason)
        assertEquals(FailureReason.HOST_NOT_FOUND, e.failures.single().reason)
    }

    @Test
    fun `NTP 결과를 맞춘 뒤 기기 시계를 바꿔도 now 가 서버 시각을 따른다`() = runBlocking {
        FakeNtpServer { NtpReply().build(it) }.use { ntp ->
            val wall = FakeWall()
            val c = clock(TimeSources.ntp("127.0.0.1", ntp.port), wall = wall)
            val first = c.now().epochMillis
            wall.now += 86_400_000L * 400 // 400일 앞으로
            val second = c.now().epochMillis
            assertTrue(second - first in 0..1_000)
            assertEquals(1, ntp.requests.size)
        }
    }
}
