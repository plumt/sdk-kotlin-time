package net.plumt.time

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

class ZoneAndJavaClockTest {

    private val mono = FakeMonotonic()
    private val wall = FakeWall(now = SERVER_TIME - 7_000)

    @Test
    fun `기본 시간대는 Asia Seoul 이다`() = runBlocking {
        val reading = builder(mono, wall, okSource(mono)).build().now()
        assertEquals("Asia/Seoul", reading.zoneId)
        assertEquals(Instant.ofEpochMilli(SERVER_TIME), reading.toInstant())
        assertEquals(LocalDateTime.parse("2026-11-03T21:00:00"), reading.toLocalDateTime())
        assertEquals(ZoneId.of("Asia/Seoul"), reading.toZonedDateTime().zone)
    }

    @Test
    fun `원하는 시간대로 받는다 — 서버 선택과 무관`() = runBlocking {
        val ny = builder(mono, wall, okSource(mono)).zone(ZoneId.of("America/New_York")).build()
        assertEquals("America/New_York", ny.zoneId)
        val reading = ny.now()
        assertEquals(SERVER_TIME, reading.epochMillis)
        assertEquals(LocalDateTime.parse("2026-11-03T07:00:00"), reading.toLocalDateTime())
        // 결과마다 다른 시간대로도 바꿀 수 있다
        assertEquals(LocalDateTime.parse("2026-11-03T12:00:00"), reading.toLocalDateTime(ZoneId.of("UTC")))
        assertEquals(ZoneId.of("Europe/London"), reading.toZonedDateTime(ZoneId.of("Europe/London")).zone)
    }

    @Test
    fun `시간대 ID 문자열 — java time 없이`() = runBlocking {
        val utc = builder(mono, wall, okSource(mono)).zone("UTC").build()
        assertEquals("UTC", utc.now().zoneId)
        val e = assertThrows<IllegalArgumentException> { TrustedClock.builder().zone("Mars/Base") }
        assertTrue(e.message!!.contains("Mars/Base"))
        assertThrows<IllegalArgumentException> { TrustedClock.builder().zone("") }
        assertThrows<IllegalArgumentException> { TrustedClock.builder().zone("asia/seoul") }
        Unit
    }

    @Test
    fun `asJavaClock — 동기화 뒤에는 보정 시각, 기기 시계 조작과 무관`() = runBlocking {
        val clock = builder(mono, wall, okSource(mono)).build()
        clock.sync()
        val java = clock.asJavaClock()
        assertEquals(ZoneId.of("Asia/Seoul"), java.zone)
        wall.now = 0
        mono.advance(250)
        assertEquals(SERVER_TIME + 250, java.millis())
        assertEquals(Instant.ofEpochMilli(SERVER_TIME + 250), java.instant())
        assertEquals(LocalDateTime.parse("2026-11-03T21:00:00.250"), LocalDateTime.now(java))
    }

    @Test
    fun `asJavaClock — 동기화가 없으면 정책 DEVICE_CLOCK 은 기기 시계, 그 밖은 NOT_SYNCED`() {
        val device = builder(mono, wall, okSource(mono)).build().asJavaClock()
        assertEquals(wall.now, device.millis())
        listOf(FallbackPolicy.LAST_SYNC_ONLY, FallbackPolicy.NONE).forEach { policy ->
            val strict = builder(mono, wall, okSource(mono)).fallbackPolicy(policy).build().asJavaClock()
            val e = assertThrows<TimeSyncException> { strict.millis() }
            assertEquals(FailureReason.NOT_SYNCED, e.reason)
        }
    }

    @Test
    fun `asJavaClock — 지난 동기화는 NONE 만 막는다`() = runBlocking {
        listOf(FallbackPolicy.DEVICE_CLOCK, FallbackPolicy.LAST_SYNC_ONLY, FallbackPolicy.NONE).forEach { policy ->
            val m = FakeMonotonic()
            val clock = builder(m, wall, okSource(m)).fallbackPolicy(policy)
                .refreshIntervalMillis(1_000).maxAgeMillis(10_000).build()
            clock.sync()
            m.advance(5_000)
            val java = clock.asJavaClock()
            if (policy == FallbackPolicy.NONE) {
                assertEquals(FailureReason.NOT_SYNCED, assertThrows<TimeSyncException> { java.millis() }.reason)
            } else {
                assertEquals(SERVER_TIME + 5_000, java.millis())
            }
        }
    }

    @Test
    fun `asJavaClock withZone·equals·hashCode·toString`() {
        val clock = builder(mono, wall, okSource(mono)).build()
        val a = clock.asJavaClock()
        assertSame(a, a.withZone(ZoneId.of("Asia/Seoul")))
        val utc = a.withZone(ZoneId.of("UTC"))
        assertEquals(ZoneId.of("UTC"), utc.zone)
        assertNotEquals(a, utc)
        assertEquals(a, clock.asJavaClock())
        assertEquals(a.hashCode(), clock.asJavaClock().hashCode())
        assertNotEquals(a, builder(mono, wall, okSource(mono)).build().asJavaClock())
        assertNotEquals(a, "x")
        assertTrue(a.toString().contains("Asia/Seoul"))
    }
}
