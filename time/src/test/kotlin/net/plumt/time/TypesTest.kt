package net.plumt.time

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TypesTest {

    private fun snapshot(
        server: Long = 1,
        anchor: Long = 2,
        unc: Long = 3,
        origin: TimeOrigin = TimeOrigin.NTP,
        name: String = "n",
        boot: String? = "b",
    ) = SyncSnapshot(server, anchor, unc, origin, name, boot)

    @Test
    fun `SyncSnapshot 검사·같음·문자열`() {
        assertTrue(assertThrows<IllegalArgumentException> { snapshot(unc = -1) }.message!!.contains("-1"))
        assertTrue(assertThrows<IllegalArgumentException> { snapshot(origin = TimeOrigin.DEVICE_CLOCK) }.message!!.contains("DEVICE_CLOCK"))
        val a = snapshot()
        assertEquals(a, snapshot())
        assertEquals(a.hashCode(), snapshot().hashCode())
        assertEquals(snapshot(boot = null).hashCode(), snapshot(boot = null).hashCode())
        listOf(
            snapshot(server = 9), snapshot(anchor = 9), snapshot(unc = 9), snapshot(origin = TimeOrigin.HTTP_DATE),
            snapshot(name = "m"), snapshot(boot = null), "x",
        ).forEach { assertNotEquals(a, it) }
        assertTrue(a.toString().contains("±3 ms"))
        assertEquals(snapshot(unc = 0).uncertaintyMillis, 0L)
    }

    @Test
    fun `TimeSample 검사`() {
        TimeSample(0, 0, 0, 0)
        assertTrue(assertThrows<IllegalArgumentException> { TimeSample(0, 0, -1, 0) }.message!!.contains("roundTripMillis"))
        assertTrue(assertThrows<IllegalArgumentException> { TimeSample(0, 0, 0, -1) }.message!!.contains("uncertaintyMillis"))
    }

    @Test
    fun `TimeReading 문자열은 출처·오차·상태를 담는다`() = runBlocking {
        val mono = FakeMonotonic()
        val ok = builder(mono, FakeWall(), okSource(mono, name = "a")).build().now().toString()
        assertTrue(ok, ok.contains("CUSTOM a") && ok.contains("±15 ms") && ok.contains("Asia/Seoul"))
        val device = builder(mono, FakeWall(), failingSource("x") { java.io.IOException() }).build().now()
        val text = device.toString()
        assertTrue(text, text.contains("DEVICE_CLOCK") && text.contains("±?") && text.contains("failure=CONNECTION_FAILED"))
        assertNull(device.sourceName)
    }

    @Test
    fun `TimeReading stale 문자열`() = runBlocking {
        val mono = FakeMonotonic()
        var fail = false
        val src = ScriptedSource("a") {
            if (fail) throw java.io.IOException()
            TimeSample(SERVER_TIME, mono.elapsedMillis(), 1, 1)
        }
        val clock = builder(mono, FakeWall(), src).refreshIntervalMillis(1_000).build()
        clock.now()
        fail = true
        mono.advance(2_000)
        assertTrue(clock.now().toString().contains("stale"))
    }

    @Test
    fun `TimeSyncException 기본값`() {
        val e = TimeSyncException(FailureReason.TIMEOUT, "m")
        assertNull(e.cause)
        assertNull(e.source)
        assertTrue(e.failures.isEmpty())
        assertEquals("m", e.message)
        assertTrue(e is RuntimeException)
        assertEquals(15, FailureReason.values().size)
    }

    @Test
    fun `기본 구현들`() {
        val wall = WallClock.system().currentTimeMillis()
        assertTrue(kotlin.math.abs(wall - System.currentTimeMillis()) < 60_000)
        assertTrue(NetworkMonitor.assumeAvailable().isNetworkAvailable())
        val store = SyncStateStore.inMemory()
        assertNull(store.load())
        val s = SyncSnapshot(1, 2, 3, TimeOrigin.NTP, "n", null)
        store.save(s)
        assertEquals(s, store.load())
        store.clear()
        assertNull(store.load())
        assertEquals(4, TimeOrigin.values().size)
    }
}
