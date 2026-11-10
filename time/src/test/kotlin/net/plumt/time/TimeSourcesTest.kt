package net.plumt.time

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TimeSourcesTest {

    private fun iae(block: () -> Unit): String = assertThrows<IllegalArgumentException>(block).message!!

    @Test
    fun `ntp 인자 검사`() {
        assertTrue(iae { TimeSources.ntp("") }.contains("비었다"))
        assertTrue(iae { TimeSources.ntp("   ") }.contains("비었다"))
        assertTrue(iae { TimeSources.ntp("a b") }.contains("공백"))
        assertTrue(iae { TimeSources.ntp("udp://x") }.contains("'/'"))
        assertTrue(iae { TimeSources.ntp("a".repeat(254)) }.contains("254"))
        TimeSources.ntp("a".repeat(253))
        TimeSources.ntp("::1")
        TimeSources.ntp("시각.한국")
        assertTrue(iae { TimeSources.ntp("x", 0) }.contains("0"))
        assertTrue(iae { TimeSources.ntp("x", 65_536) }.contains("65536"))
        assertEquals("x:1", TimeSources.ntp("x", 1).name)
        assertEquals("x:65535", TimeSources.ntp("x", 65_535).name)
        assertEquals("x", TimeSources.ntp("x").name)
        assertEquals("x", TimeSources.ntp("x", 123).name)
    }

    @Test
    fun `httpDate 인자 검사`() {
        assertTrue(iae { TimeSources.httpDate("ht tp://x") }.contains("형식"))
        assertTrue(iae { TimeSources.httpDate("ftp://x.example") }.contains("https"))
        assertTrue(iae { TimeSources.httpDate("x.example") }.contains("https"))
        assertTrue(iae { TimeSources.httpDate("http://x.example") }.contains("https"))
        assertTrue(iae { TimeSources.httpDate("https:///path") }.contains("호스트"))
        assertTrue(iae { TimeSources.httpDate("https://user:pw@example.com/") }.contains("사용자 정보"))
        assertEquals("https://x.example/", TimeSources.httpDate("https://x.example/").name)
        assertEquals("HTTPS://x.example", TimeSources.httpDate("HTTPS://x.example").name)
        assertEquals(TimeOrigin.HTTP_DATE, TimeSources.httpDateAllowingCleartext("http://x.example").origin)
        assertTrue(iae { TimeSources.httpDateAllowingCleartext("ftp://x.example") }.contains("http"))
    }

    @Test
    fun `기본 서버 목록은 모두 유효한 출처가 된다`() {
        (TimeServers.KOREA_NTP + TimeServers.GLOBAL_NTP).forEach { TimeSources.ntp(it) }
        (TimeServers.KOREA_HTTP + TimeServers.GLOBAL_HTTP).forEach { TimeSources.httpDate(it) }
        assertTrue("kr.pool.ntp.org" in TimeServers.KOREA_NTP)
        assertTrue("time.google.com" in TimeServers.KOREA_NTP)
        assertTrue("pool.ntp.org" in TimeServers.GLOBAL_NTP)
        assertTrue("time.cloudflare.com" in TimeServers.GLOBAL_NTP)
        // 공개 목록은 Java 에서도 바꿀 수 없다
        listOf(TimeServers.KOREA_NTP, TimeServers.GLOBAL_NTP, TimeServers.KOREA_HTTP, TimeServers.GLOBAL_HTTP).forEach { list ->
            assertThrows<UnsupportedOperationException> { (list as java.util.List<String>).set(0, "evil") }
        }
    }

    @Test
    fun `내장 출처의 공개 query 는 실패를 결과로 돌려준다`() {
        val query = TimeQuery(200, FakeMonotonic())
        val bad = TimeSources.ntp("nowhere.invalid").query(query)
        assertTrue(bad is TimeSourceResult.Failure)
        bad as TimeSourceResult.Failure
        assertTrue(bad.reason == FailureReason.HOST_NOT_FOUND || bad.reason == FailureReason.NO_NETWORK || bad.reason == FailureReason.CONNECTION_FAILED)
        assertTrue(bad.message.startsWith("nowhere.invalid"))
        net.plumt.time.FakeHttpServer { net.plumt.time.FakeHttpServer.response("Date: Tue, 03 Nov 2026 12:00:00 GMT") }.use { http ->
            val ok = TimeSources.httpDateAllowingCleartext(http.url).query(TimeQuery(2_000, FakeMonotonic()))
            assertTrue(ok.toString(), ok is TimeSourceResult.Success)
            assertEquals(SERVER_TIME + 500, (ok as TimeSourceResult.Success).sample.serverEpochMillis)
        }
    }
}
