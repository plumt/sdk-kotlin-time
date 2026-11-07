package net.plumt.time

import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BuilderTest {

    private fun iae(block: () -> Unit): String = assertThrows<IllegalArgumentException>(block).message!!

    @Test
    fun `기본값으로 만든다 — 한국 서버·Asia Seoul`() {
        val clock = TrustedClock.builder().build()
        assertEquals("Asia/Seoul", clock.zoneId)
        assertTrue(clock.peek() == null)
    }

    @Test
    fun `해외 서버 목록으로 바꾼다`() {
        val clock = TrustedClock.builder().ntpServers(TimeServers.GLOBAL_NTP).httpDateUrls(TimeServers.GLOBAL_HTTP)
            .zone("UTC").dispatcher(Dispatchers.IO).networkMonitor(NetworkMonitor.assumeAvailable())
            .wallClock(WallClock.system()).monotonicClock(MonotonicClock.system()).store(SyncStateStore.inMemory())
            .build()
        assertEquals("UTC", clock.zoneId)
    }

    @Test
    fun `NTP 만, HTTP 만 쓸 수 있고 둘 다 비면 build 가 거부한다`() {
        TrustedClock.builder().httpDateUrls(emptyList()).build()
        TrustedClock.builder().ntpServers(emptyList()).build()
        val m = iae { TrustedClock.builder().ntpServers(emptyList()).httpDateUrls(emptyList()).build() }
        assertTrue(m.contains("출처가 하나도 없다"))
    }

    @Test
    fun `목록 설정은 바로 검사한다`() {
        assertTrue(iae { TrustedClock.builder().ntpServers(listOf("ok.example", " ")) }.contains("host"))
        assertTrue(iae { TrustedClock.builder().httpDateUrls(listOf("http://plain.example")) }.contains("https"))
    }

    @Test
    fun `sources — 빈 목록·기기 시계 출처 거부`() {
        assertTrue(iae { TrustedClock.builder().sources(emptyList()) }.contains("비었다"))
        val device = ScriptedSource("d", TimeOrigin.DEVICE_CLOCK) { error("불리지 않는다") }
        assertTrue(iae { TrustedClock.builder().sources(listOf(device)) }.contains("DEVICE_CLOCK"))
    }

    @Test
    fun `숫자 설정의 경계`() {
        val b = TrustedClock.builder()
        b.timeoutMillis(100).timeoutMillis(60_000)
        assertTrue(iae { b.timeoutMillis(99) }.contains("99"))
        assertTrue(iae { b.timeoutMillis(60_001) }.contains("100..60000"))
        b.maxRoundTripMillis(1).maxRoundTripMillis(60_000)
        assertTrue(iae { b.maxRoundTripMillis(0) }.contains("0"))
        assertTrue(iae { b.maxRoundTripMillis(60_001) }.contains("60001"))
        b.refreshIntervalMillis(1_000).refreshIntervalMillis(30L * 86_400_000)
        assertTrue(iae { b.refreshIntervalMillis(999) }.contains("999"))
        assertTrue(iae { b.refreshIntervalMillis(30L * 86_400_000 + 1) }.contains("refreshIntervalMillis"))
        b.maxAgeMillis(1_000).maxAgeMillis(30L * 86_400_000)
        assertTrue(iae { b.maxAgeMillis(999) }.contains("maxAgeMillis"))
        assertTrue(iae { b.maxAgeMillis(Long.MAX_VALUE) }.contains("maxAgeMillis"))
    }

    @Test
    fun `최대 보관 기간이 갱신 주기보다 짧으면 build 거부, 같으면 받는다`() {
        TrustedClock.builder().refreshIntervalMillis(5_000).maxAgeMillis(5_000).build()
        val m = iae { TrustedClock.builder().refreshIntervalMillis(5_000).maxAgeMillis(4_999).build() }
        assertTrue(m.contains("4999") && m.contains("5000"))
    }

    @Test
    fun `정책 세 가지를 모두 받는다`() {
        FallbackPolicy.values().forEach { TrustedClock.builder().fallbackPolicy(it).build() }
        assertEquals(3, FallbackPolicy.values().size)
    }
}
