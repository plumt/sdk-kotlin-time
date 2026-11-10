package net.plumt.time

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DateTimeException
import java.time.Instant
import java.time.ZoneId

class FakeTrustedClockTest {

    @Test
    fun `정한 시각을 돌려주고 흘린다`() = runBlocking {
        val clock = FakeTrustedClock(SERVER_TIME)
        assertEquals("Asia/Seoul", clock.zoneId)
        val reading = (clock.now() as TimeResult.Success).reading
        assertEquals(SERVER_TIME, reading.epochMillis)
        assertSame(TimeOrigin.NTP, reading.origin)
        assertEquals(0L, reading.uncertaintyMillis)
        assertEquals("fake", reading.sourceName)
        assertFalse(reading.isStale)
        assertNull(reading.failure)
        clock.advanceBy(1_000)
        assertEquals(SERVER_TIME + 1_000, clock.sync().orThrow().epochMillis)
        clock.epochMillis = 5
        assertEquals(5L, clock.peek()!!.epochMillis)
        assertEquals(5L, clock.epochMillis)
        assertEquals(1, clock.nowCalls)
        assertEquals(1, clock.syncCalls)
    }

    @Test
    fun `실패를 정하고 되돌린다`() = runBlocking {
        val clock = FakeTrustedClock(SERVER_TIME, "UTC")
        assertEquals("UTC", clock.zoneId)
        clock.failWith(FailureReason.NO_NETWORK)
        val failure = (clock.now() as TimeResult.Failure).failure
        assertSame(FailureReason.NO_NETWORK, failure.reason)
        assertTrue(clock.sync() is TimeResult.Failure)
        assertNull(clock.peek())
        assertTrue(clock.toString().contains("NO_NETWORK"))
        assertThrows<DateTimeException> { clock.asJavaClock().millis() }
        clock.succeed()
        assertTrue(clock.now() is TimeResult.Success)
    }

    @Test
    fun `콜백은 부른 스레드에서 바로 주고 끝난 핸들을 돌려준다`() {
        val clock = FakeTrustedClock(SERVER_TIME)
        val got = mutableListOf<TimeResult>()
        val a = clock.now { got += it }
        val b = clock.sync { got += it }
        assertEquals(2, got.size)
        assertFalse(a.isActive)
        a.cancel()
        assertFalse(b.isActive)
        assertTrue(a.toString().contains("active=false"))
        assertEquals(1, clock.nowCalls)
        assertEquals(1, clock.syncCalls)
    }

    @Test
    fun `invalidate 횟수와 java Clock`() {
        val clock = FakeTrustedClock(SERVER_TIME)
        clock.invalidate()
        clock.invalidate()
        assertEquals(2, clock.invalidateCalls)
        val java = clock.asJavaClock()
        assertEquals(ZoneId.of("Asia/Seoul"), java.zone)
        assertEquals(SERVER_TIME, java.millis())
        assertEquals(Instant.ofEpochMilli(SERVER_TIME), java.instant())
        assertEquals(ZoneId.of("UTC"), java.withZone(ZoneId.of("UTC")).zone)
    }
}
