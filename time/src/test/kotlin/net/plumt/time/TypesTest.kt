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
    fun `상수 클래스 — 이름·문자열·목록은 바꿀 수 없다`() {
        assertEquals(14, FailureReason.values.size)
        assertEquals(FailureReason.values.map { it.name }.toSet().size, FailureReason.values.size)
        assertEquals("TIMEOUT", FailureReason.TIMEOUT.toString())
        assertEquals(listOf("NTP", "HTTP_DATE", "CUSTOM", "DEVICE_CLOCK"), TimeOrigin.values.map { it.name })
        assertEquals("NTP", TimeOrigin.NTP.toString())
        assertThrows<UnsupportedOperationException> { (FailureReason.values as java.util.List<FailureReason>).add(FailureReason.TIMEOUT) }
        assertEquals(TimeOrigin.HTTP_DATE, TimeOrigin.fromStorageKey("http-date"))
        assertEquals(TimeOrigin.HTTP_DATE, TimeOrigin.fromLegacyName("HTTP_DATE"))
        assertNull(TimeOrigin.fromStorageKey("HTTP_DATE"))
        assertNull(TimeOrigin.fromLegacyName(null))
    }

    @Test
    fun `결과 타입 — 같음·문자열`() {
        val f = net.plumt.time.internal.SyncError(FailureReason.TIMEOUT, "m", null, "s").failure
        assertEquals("m", f.message)
        assertEquals("s", f.source)
        assertTrue(f.attempts.isEmpty())
        assertEquals(f, net.plumt.time.internal.SyncError(FailureReason.TIMEOUT, "m", null, "s").failure)
        assertEquals(f.hashCode(), net.plumt.time.internal.SyncError(FailureReason.TIMEOUT, "m", null, "s").failure.hashCode())
        assertNotEquals(f, net.plumt.time.internal.SyncError(FailureReason.TIMEOUT, "m").failure)
        assertNotEquals(f, "x")
        assertTrue(f.toString().contains("TIMEOUT"))
        val failure = TimeResult.Failure(f)
        assertEquals(failure, TimeResult.Failure(f))
        assertEquals(failure.hashCode(), TimeResult.Failure(f).hashCode())
        assertNotEquals(failure, "x")
        assertTrue(failure.toString().startsWith("Failure("))
        val reading = FakeTrustedClock(SERVER_TIME).peek()!!
        val success = TimeResult.Success(reading)
        assertEquals(success, TimeResult.Success(FakeTrustedClock(SERVER_TIME).peek()!!))
        assertEquals(success.hashCode(), TimeResult.Success(FakeTrustedClock(SERVER_TIME).peek()!!).hashCode())
        assertNotEquals(success, TimeResult.Success(FakeTrustedClock(SERVER_TIME + 1).peek()!!))
        assertNotEquals(reading, "x")
        assertTrue(success.toString().startsWith("Success("))
        val sample = TimeSample(1, 2, 3, 4)
        assertEquals(sample, TimeSample(1, 2, 3, 4))
        assertEquals(sample.hashCode(), TimeSample(1, 2, 3, 4).hashCode())
        listOf(TimeSample(9, 2, 3, 4), TimeSample(1, 9, 3, 4), TimeSample(1, 2, 9, 4), TimeSample(1, 2, 3, 9), "x")
            .forEach { assertNotEquals(sample, it) }
        assertTrue(sample.toString().contains("rtt=3"))
        val ok = TimeSourceResult.Success(sample)
        assertEquals(ok, TimeSourceResult.Success(TimeSample(1, 2, 3, 4)))
        assertEquals(ok.hashCode(), TimeSourceResult.Success(TimeSample(1, 2, 3, 4)).hashCode())
        assertNotEquals(ok, "x")
        assertTrue(ok.toString().startsWith("Success("))
        val io = java.io.IOException()
        val bad = TimeSourceResult.Failure(FailureReason.TIMEOUT, "m", io)
        assertEquals(bad, TimeSourceResult.Failure(FailureReason.TIMEOUT, "m", io))
        assertEquals(bad.hashCode(), TimeSourceResult.Failure(FailureReason.TIMEOUT, "m", io).hashCode())
        assertNotEquals(bad, TimeSourceResult.Failure(FailureReason.TIMEOUT, "m"))
        assertEquals(TimeSourceResult.Failure(FailureReason.TIMEOUT, "m").hashCode(), TimeSourceResult.Failure(FailureReason.TIMEOUT, "m").hashCode())
        assertNotEquals(bad, "x")
        assertTrue(bad.toString().contains("TIMEOUT"))
        assertTrue(TimeQuery(5, FakeMonotonic()).toString().contains("5"))
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
        assertEquals(4, TimeOrigin.values.size)
    }
}
