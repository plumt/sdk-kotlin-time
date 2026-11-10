package net.plumt.time

import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class TimeConfigTest {

    private fun iae(block: () -> Unit): String = assertThrows<IllegalArgumentException>(block).message!!

    @Test
    fun `기본값 — 한국 서버·Asia Seoul·기기 시계 폴백`() {
        val c = TimeConfig.builder().build()
        assertEquals("Asia/Seoul", c.zoneId)
        assertEquals(TimeServers.KOREA_NTP, c.ntpServers)
        assertEquals(TimeServers.KOREA_HTTP, c.httpDateUrls)
        assertNull(c.sources)
        assertEquals(3_000, c.timeoutMillis)
        assertEquals(1_500L, c.maxRoundTripMillis)
        assertEquals(6L * 3_600_000, c.refreshIntervalMillis)
        assertEquals(24L * 3_600_000, c.maxAgeMillis)
        assertSame(FallbackPolicy.DeviceClock, c.fallbackPolicy)
        assertNull(c.monotonicClock)
        assertNull(c.wallClock)
        assertNull(c.networkMonitor)
        assertNull(c.store)
        assertSame(Dispatchers.IO, c.dispatcher)
        assertEquals(5, c.resolvedSources.size)
        assertTrue(c.toString().contains("time.kriss.re.kr"))
    }

    @Test
    fun `모든 설정을 바꾸고 toBuilder 로 그대로 옮긴다`() {
        val mono = FakeMonotonic()
        val wall = FakeWall()
        val net = NetworkMonitor.assumeAvailable()
        val store = SyncStateStore.inMemory()
        val c = TimeConfig.builder().ntpServers(TimeServers.GLOBAL_NTP).httpDateUrls(TimeServers.GLOBAL_HTTP)
            .zone("UTC").timeoutMillis(500).maxRoundTripMillis(700).refreshIntervalMillis(2_000).maxAgeMillis(3_000)
            .fallbackPolicy(FallbackPolicy.None).monotonicClock(mono).wallClock(wall).networkMonitor(net).store(store)
            .dispatcher(Dispatchers.Default).build()
        val copy = c.toBuilder().build()
        for (x in listOf(c, copy)) {
            assertEquals(TimeServers.GLOBAL_NTP, x.ntpServers)
            assertEquals(TimeServers.GLOBAL_HTTP, x.httpDateUrls)
            assertEquals("UTC", x.zoneId)
            assertEquals(500, x.timeoutMillis)
            assertEquals(700L, x.maxRoundTripMillis)
            assertEquals(2_000L, x.refreshIntervalMillis)
            assertEquals(3_000L, x.maxAgeMillis)
            assertSame(FallbackPolicy.None, x.fallbackPolicy)
            assertSame(mono, x.monotonicClock)
            assertSame(wall, x.wallClock)
            assertSame(net, x.networkMonitor)
            assertSame(store, x.store)
            assertSame(Dispatchers.Default, x.dispatcher)
        }
        val src = okSource(mono)
        val custom = c.toBuilder().sources(listOf(src)).zone(java.time.ZoneId.of("Europe/London")).build()
        assertEquals(listOf(src), custom.sources)
        assertEquals(listOf(src), custom.toBuilder().build().resolvedSources)
        assertEquals("Europe/London", custom.zoneId)
    }

    @Test
    fun `설정 목록은 사본이고 바꿀 수 없다`() {
        val hosts = mutableListOf("a.example")
        val c = TimeConfig.builder().ntpServers(hosts).build()
        hosts += "b.example"
        assertEquals(listOf("a.example"), c.ntpServers)
        assertThrows<UnsupportedOperationException> { (c.ntpServers as java.util.List<String>).add("x") }
    }

    @Test
    fun `NTP 만, HTTP 만 쓸 수 있고 둘 다 비면 build 가 거부한다`() {
        TimeConfig.builder().httpDateUrls(emptyList()).build()
        TimeConfig.builder().ntpServers(emptyList()).build()
        val m = iae { TimeConfig.builder().ntpServers(emptyList()).httpDateUrls(emptyList()).build() }
        assertTrue(m.contains("출처가 하나도 없다"))
    }

    @Test
    fun `목록 설정은 바로 검사한다`() {
        assertTrue(iae { TimeConfig.builder().ntpServers(listOf("ok.example", " ")) }.contains("host"))
        assertTrue(iae { TimeConfig.builder().httpDateUrls(listOf("http://plain.example")) }.contains("https"))
    }

    @Test
    fun `sources — 빈 목록·기기 시계 출처 거부`() {
        assertTrue(iae { TimeConfig.builder().sources(emptyList()) }.contains("비었다"))
        val device = ScriptedSource("d", TimeOrigin.DEVICE_CLOCK) { error("불리지 않는다") }
        assertTrue(iae { TimeConfig.builder().sources(listOf(device)) }.contains("DEVICE_CLOCK"))
    }

    @Test
    fun `시간대 ID 검사`() {
        assertTrue(iae { TimeConfig.builder().zone("Mars/Base") }.contains("Mars/Base"))
    }

    @Test
    fun `숫자 설정의 경계`() {
        val b = TimeConfig.builder()
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
        TimeConfig.builder().refreshIntervalMillis(5_000).maxAgeMillis(5_000).build()
        val m = iae { TimeConfig.builder().refreshIntervalMillis(5_000).maxAgeMillis(4_999).build() }
        assertTrue(m.contains("4999") && m.contains("5000"))
    }

    @Test
    fun `정책 세 가지는 닫힌 집합이다`() {
        val all = listOf(FallbackPolicy.DeviceClock, FallbackPolicy.LastSyncOnly, FallbackPolicy.None)
        all.forEach { TimeConfig.builder().fallbackPolicy(it).build() }
        // else 없는 when 이 컴파일되면 닫힌 집합이다
        val names = all.map {
            when (it) {
                FallbackPolicy.DeviceClock -> "d"
                FallbackPolicy.LastSyncOnly -> "l"
                FallbackPolicy.None -> "n"
            }
        }
        assertEquals(listOf("d", "l", "n"), names)
        assertEquals("DeviceClock", FallbackPolicy.DeviceClock.toString())
    }
}
