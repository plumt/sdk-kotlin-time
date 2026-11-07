package net.plumt.time.internal

import net.plumt.time.FailureReason
import net.plumt.time.FakeHttpServer
import net.plumt.time.FakeNtpServer
import net.plumt.time.MonotonicClock
import net.plumt.time.NtpReply
import net.plumt.time.SERVER_TIME
import net.plumt.time.TimeOrigin
import net.plumt.time.TimeQuery
import net.plumt.time.TimeSources
import net.plumt.time.TimeSyncException
import net.plumt.time.assertThrows
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ConnectException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException

/** 로컬 가짜 서버로 실제 소켓 경로를 시험한다. */
class NetworkSourcesTest {

    private val query = TimeQuery(timeoutMillis = 1_000, monotonicClock = MonotonicClock.system())
    private val fastTimeout = TimeQuery(timeoutMillis = 200, monotonicClock = MonotonicClock.system())

    private fun ntp(server: FakeNtpServer) = TimeSources.ntp("127.0.0.1", server.port)

    // ---- NTP ----

    @Test
    fun `NTP 정상 — 서버 시각과 기준점을 돌려준다`() {
        FakeNtpServer { NtpReply(transmit = SERVER_TIME).build(it) }.use { server ->
            val source = ntp(server)
            assertEquals("127.0.0.1:${server.port}", source.name)
            assertEquals(TimeOrigin.NTP, source.origin)
            val before = query.monotonicClock.elapsedMillis()
            val sample = source.query(query)
            assertTrue(sample.anchorElapsedMillis >= before)
            assertTrue(sample.serverEpochMillis in SERVER_TIME..SERVER_TIME + sample.roundTripMillis)
            assertTrue(sample.uncertaintyMillis >= 1)
            // 요청 형식: 48바이트, 모드 3
            val request = server.requests.single()
            assertEquals(48, request.size)
            assertEquals(3, request[0].toInt() and 0b111)
        }
    }

    @Test
    fun `NTP 포트 123 이면 이름에 포트를 붙이지 않는다`() {
        assertEquals("time.example.com", TimeSources.ntp("time.example.com").name)
        assertEquals("time.example.com:1123", TimeSources.ntp("time.example.com", 1123).name)
    }

    @Test
    fun `NTP 응답 없음은 SocketTimeoutException`() {
        FakeNtpServer { null }.use { server ->
            assertThrows<SocketTimeoutException> { ntp(server).query(fastTimeout) }
        }
    }

    @Test
    fun `NTP KoD 는 KISS_OF_DEATH`() {
        FakeNtpServer { NtpReply(stratum = 0, referenceId = "RATE".toByteArray()).build(it) }.use { server ->
            val e = assertThrows<TimeSyncException> { ntp(server).query(query) }
            assertEquals(FailureReason.KISS_OF_DEATH, e.reason)
            assertEquals("127.0.0.1:${server.port}", e.source)
        }
    }

    @Test
    fun `NTP 원본 시각 위조는 INVALID_RESPONSE`() {
        FakeNtpServer { NtpReply(originateOverride = ByteArray(8) { 7 }).build(it) }.use { server ->
            assertEquals(FailureReason.INVALID_RESPONSE, assertThrows<TimeSyncException> { ntp(server).query(query) }.reason)
        }
    }

    @Test
    fun `NTP 잘린 응답과 빈 응답은 요청 바이트를 응답으로 읽지 않고 INVALID_RESPONSE`() {
        listOf(20, 0).forEach { length ->
            FakeNtpServer { NtpReply(length = length).build(it) }.use { server ->
                val e = assertThrows<TimeSyncException> { ntp(server).query(query) }
                assertEquals("길이 $length", FailureReason.INVALID_RESPONSE, e.reason)
                assertTrue(e.message!!.contains(": $length"))
            }
        }
    }

    @Test
    fun `NTP 호스트 이름 조회 실패는 UnknownHostException`() {
        val source = NtpTimeSource("nowhere.invalid", 123, resolver = { throw UnknownHostException(it) })
        assertThrows<UnknownHostException> { source.query(query) }
    }

    @Test
    fun `NTP 난수가 0 이면 1 로 바꾼다 — 기본 난수 공급`() {
        FakeNtpServer { NtpReply().build(it) }.use { server ->
            val source = NtpTimeSource("127.0.0.1", server.port, InetAddress::getByName)
            source.query(query)
            val request = server.requests.single()
            assertTrue((40 until 48).any { request[it] != 0.toByte() })
        }
    }

    // ---- HTTP ----

    @Test
    fun `HTTP 정상 — HEAD 와 캐시 끄기 헤더로 묻고 Date 에 반 초와 왕복 절반을 더한다`() {
        FakeHttpServer { FakeHttpServer.response("Date: Tue, 03 Nov 2026 12:00:00 GMT") }.use { server ->
            val source = TimeSources.httpDate(server.url, allowCleartext = true)
            assertEquals(TimeOrigin.HTTP_DATE, source.origin)
            val sample = source.query(query)
            assertEquals(SERVER_TIME + 500 + sample.roundTripMillis / 2, sample.serverEpochMillis)
            assertEquals(500 + (sample.roundTripMillis + 1) / 2 + 1, sample.uncertaintyMillis)
            val request = server.requests.single()
            assertTrue(request.first(), request.first().startsWith("HEAD / HTTP/1.1"))
            assertTrue(request.any { it.equals("Cache-Control: no-cache", ignoreCase = true) })
        }
    }

    @Test
    fun `HTTP 리다이렉트를 따라가지 않고 그 응답의 Date 를 쓴다`() {
        FakeHttpServer {
            "HTTP/1.1 301 Moved\r\nLocation: http://127.0.0.1:1/\r\nDate: Tue, 03 Nov 2026 12:00:00 GMT\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
        }.use { server ->
            val sample = TimeSources.httpDate(server.url, allowCleartext = true).query(query)
            assertTrue(sample.serverEpochMillis >= SERVER_TIME + 500)
            assertEquals(1, server.requests.size)
        }
    }

    @Test
    fun `HTTP Date 없음은 HTTP_DATE_MISSING`() {
        FakeHttpServer { FakeHttpServer.response("Server: fake") }.use { server ->
            val e = assertThrows<TimeSyncException> { TimeSources.httpDate(server.url, true).query(query) }
            assertEquals(FailureReason.HTTP_DATE_MISSING, e.reason)
            assertEquals(server.url, e.source)
        }
    }

    @Test
    fun `HTTP Date 파싱 실패는 HTTP_DATE_INVALID 이고 헤더 앞부분을 메시지에 담는다`() {
        FakeHttpServer { FakeHttpServer.response("Date: 2026-11-03T12:00:00Z") }.use { server ->
            val e = assertThrows<TimeSyncException> { TimeSources.httpDate(server.url, true).query(query) }
            assertEquals(FailureReason.HTTP_DATE_INVALID, e.reason)
            assertTrue(e.message!!.contains("2026-11-03T12:00:00Z"))
        }
    }

    @Test
    fun `HTTP 응답이 늦으면 SocketTimeoutException`() {
        FakeHttpServer {
            Thread.sleep(1_000)
            FakeHttpServer.response("Date: Tue, 03 Nov 2026 12:00:00 GMT")
        }.use { server ->
            assertThrows<SocketTimeoutException> { TimeSources.httpDate(server.url, true).query(fastTimeout) }
        }
    }

    @Test
    fun `HTTP 연결 거부는 ConnectException`() {
        val port = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort }
        assertThrows<ConnectException> { TimeSources.httpDate("http://127.0.0.1:$port/", true).query(query) }
    }

    @Test
    fun `HTTP 연결을 끝에서 끊는다`() {
        var disconnected = false
        val connection = object : java.net.HttpURLConnection(URL("http://127.0.0.1/")) {
            override fun connect() = Unit
            override fun disconnect() {
                disconnected = true
            }
            override fun usingProxy() = false
            override fun getResponseCode(): Int = 200
            override fun getHeaderField(name: String?): String? = null
        }
        val source = HttpDateTimeSource("x", URL("http://127.0.0.1/"), open = { connection })
        assertThrows<TimeSyncException> { source.query(query) }
        assertTrue(disconnected)
    }
}
