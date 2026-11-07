package net.plumt.time

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.time.Instant

class TrustedClockTest {

    private val mono = FakeMonotonic()
    private val wall = FakeWall()

    @Test
    fun `첫 now 는 동기화하고 출처·오차·나이를 담는다`() = runBlocking {
        val source = okSource(mono, name = "a", roundTrip = 30, uncertainty = 12)
        val clock = builder(mono, wall, source).build()
        val reading = clock.now()
        assertEquals(SERVER_TIME, reading.epochMillis)
        assertEquals(TimeOrigin.CUSTOM, reading.origin)
        assertEquals("a", reading.sourceName)
        assertEquals(12L, reading.uncertaintyMillis)
        assertEquals(0L, reading.ageMillis)
        assertFalse(reading.isStale)
        assertFalse(reading.isDeviceFallback)
        assertNull(reading.failure)
        assertEquals(1, source.calls.get())
    }

    @Test
    fun `동기화 뒤에는 네트워크 없이 단조 시계 경과만 더한다`() = runBlocking {
        val source = okSource(mono)
        val clock = builder(mono, wall, source).build()
        clock.now()
        mono.advance(60_000)
        val reading = clock.now()
        assertEquals(SERVER_TIME + 60_000, reading.epochMillis)
        assertEquals(60_000L, reading.ageMillis)
        // 오차는 경과 × 100ppm(올림)만큼 는다: 60000 × 100 / 10^6 = 6
        assertEquals(15L + 6, reading.uncertaintyMillis)
        assertEquals(1, source.calls.get())
    }

    @Test
    fun `사용자가 기기 시계를 바꿔도 now 는 그대로다`() = runBlocking {
        val source = okSource(mono)
        val clock = builder(mono, wall, source).build()
        clock.now()
        wall.now = SERVER_TIME + 5 * 3_600_000L // 5시간 앞으로
        mono.advance(1_000)
        val ahead = clock.now()
        assertEquals(SERVER_TIME + 1_000, ahead.epochMillis)
        assertEquals(1_000 - 5 * 3_600_000L, ahead.deviceClockOffsetMillis)

        wall.now = Instant.parse("2001-01-01T00:00:00Z").toEpochMilli() // 25년 뒤로
        mono.advance(1_000)
        val behind = clock.now()
        assertEquals(SERVER_TIME + 2_000, behind.epochMillis)
        assertTrue(behind.deviceClockOffsetMillis > 0)
        assertEquals(SERVER_TIME + 2_000, clock.peek()!!.epochMillis)
        assertEquals(SERVER_TIME + 2_000, clock.asJavaClock().millis())
        assertEquals(1, source.calls.get())
    }

    @Test
    fun `기기 날짜가 몇 년 틀린 기기에서도 서버 시각을 받아들인다`() = runBlocking {
        wall.now = Instant.parse("2001-01-01T00:00:00Z").toEpochMilli()
        val clock = builder(mono, wall, okSource(mono)).build()
        assertEquals(SERVER_TIME, clock.now().epochMillis)
    }

    @Test
    fun `갱신 주기가 지나면 now 가 다시 동기화한다 — 정확히 주기와 같으면 그대로`() = runBlocking {
        var server = SERVER_TIME
        val source = okSource(mono, serverTime = { server })
        val clock = builder(mono, wall, source).refreshIntervalMillis(10_000).maxAgeMillis(20_000).build()
        clock.now()
        mono.advance(10_000)
        assertEquals(SERVER_TIME + 10_000, clock.now().epochMillis)
        assertEquals(1, source.calls.get())
        mono.advance(1)
        server = SERVER_TIME + 50_000
        val refreshed = clock.now()
        assertEquals(2, source.calls.get())
        assertEquals(SERVER_TIME + 50_000, refreshed.epochMillis)
        assertEquals(0L, refreshed.ageMillis)
    }

    @Test
    fun `갱신 실패 시 지난 동기화로 물러나고 실패 이유를 담는다`() = runBlocking {
        var fail = false
        val source = ScriptedSource("a") {
            if (fail) throw SocketTimeoutException("늦다")
            TimeSample(SERVER_TIME, mono.elapsedMillis(), 10, 10)
        }
        val clock = builder(mono, wall, source).refreshIntervalMillis(10_000).maxAgeMillis(20_000).build()
        clock.now()
        fail = true
        mono.advance(15_000)
        val stale = clock.now()
        assertTrue(stale.isStale)
        assertEquals(TimeOrigin.CUSTOM, stale.origin)
        assertEquals(SERVER_TIME + 15_000, stale.epochMillis)
        assertEquals(FailureReason.TIMEOUT, stale.failure!!.reason)
        // peek 도 지난 동기화를 stale 로 표시하고 마지막 실패를 담는다
        val peeked = clock.peek()!!
        assertTrue(peeked.isStale)
        assertSame(stale.failure, peeked.failure)
    }

    @Test
    fun `최대 보관 기간을 넘기면 기기 시계로 물러난다 — 정확히 같으면 아직 쓴다`() = runBlocking {
        var fail = false
        val source = ScriptedSource("a") {
            if (fail) throw IOException("끊김")
            TimeSample(SERVER_TIME, mono.elapsedMillis(), 10, 10)
        }
        val clock = builder(mono, wall, source).refreshIntervalMillis(10_000).maxAgeMillis(20_000).build()
        clock.now()
        fail = true
        mono.advance(20_000)
        assertTrue(clock.now().isStale)
        mono.advance(1)
        assertNull(clock.peek())
        val device = clock.now()
        assertTrue(device.isDeviceFallback)
        assertEquals(wall.now, device.epochMillis)
        assertNull(device.uncertaintyMillis)
        assertNull(device.sourceName)
        assertEquals(0L, device.deviceClockOffsetMillis)
        assertEquals(FailureReason.CONNECTION_FAILED, device.failure!!.reason)
    }

    @Test
    fun `정책 DEVICE_CLOCK — 동기화가 한 번도 없으면 기기 시계`() = runBlocking {
        val clock = builder(mono, wall, failingSource("a") { SocketTimeoutException() }).build()
        val reading = clock.now()
        assertEquals(TimeOrigin.DEVICE_CLOCK, reading.origin)
        assertTrue(reading.isDeviceFallback)
        assertEquals(FailureReason.TIMEOUT, reading.failure!!.reason)
        assertEquals(wall.now, reading.epochMillis)
    }

    @Test
    fun `정책 LAST_SYNC_ONLY — 지난 동기화는 쓰고 기기 시계는 막는다`() = runBlocking {
        var fail = false
        val source = ScriptedSource("a") {
            if (fail) throw SocketTimeoutException()
            TimeSample(SERVER_TIME, mono.elapsedMillis(), 10, 10)
        }
        val clock = builder(mono, wall, source).fallbackPolicy(FallbackPolicy.LAST_SYNC_ONLY)
            .refreshIntervalMillis(10_000).maxAgeMillis(20_000).build()
        fail = true
        val first = assertThrowsSuspend<TimeSyncException> { clock.now() }
        assertEquals(FailureReason.TIMEOUT, first.reason)
        fail = false
        clock.sync()
        fail = true
        mono.advance(15_000)
        assertTrue(clock.now().isStale)
        mono.advance(10_000)
        val gone = assertThrowsSuspend<TimeSyncException> { clock.now() }
        assertEquals(FailureReason.TIMEOUT, gone.reason)
    }

    @Test
    fun `정책 NONE — 지난 동기화도 쓰지 않는다`() = runBlocking {
        var fail = false
        val source = ScriptedSource("a") {
            if (fail) throw SocketTimeoutException()
            TimeSample(SERVER_TIME, mono.elapsedMillis(), 10, 10)
        }
        val clock = builder(mono, wall, source).fallbackPolicy(FallbackPolicy.NONE)
            .refreshIntervalMillis(10_000).maxAgeMillis(20_000).build()
        clock.now()
        fail = true
        mono.advance(15_000)
        assertEquals(FailureReason.TIMEOUT, assertThrowsSuspend<TimeSyncException> { clock.now() }.reason)
        // 대기 중에도 같은 실패를 던진다
        assertEquals(FailureReason.TIMEOUT, assertThrowsSuspend<TimeSyncException> { clock.now() }.reason)
    }

    @Test
    fun `NTP 실패 → HTTP → 다음 출처 순서로 폴백하고 첫 성공에서 멈춘다`() = runBlocking {
        val ntp = failingSource("ntp") { SocketTimeoutException() }
        val http = okSource(mono, name = "http", origin = TimeOrigin.HTTP_DATE)
        val never = okSource(mono, name = "never")
        val reading = builder(mono, wall, ntp, http, never).build().now()
        assertEquals(TimeOrigin.HTTP_DATE, reading.origin)
        assertEquals("http", reading.sourceName)
        assertEquals(listOf(1, 1, 0), listOf(ntp.calls.get(), http.calls.get(), never.calls.get()))
    }

    @Test
    fun `묶음 실패 — 원인이 하나로 모이면 그 원인, 섞이면 ALL_SOURCES_FAILED`() = runBlocking {
        suspend fun reasonOf(vararg errors: () -> Throwable): TimeSyncException {
            val sources = errors.mapIndexed { i, e -> failingSource("s$i", e) }.toTypedArray()
            return assertThrowsSuspend { builder(mono, wall, *sources).build().sync() }
        }
        assertEquals(FailureReason.TIMEOUT, reasonOf({ SocketTimeoutException() }, { SocketTimeoutException() }).reason)
        assertEquals(FailureReason.NO_NETWORK, reasonOf({ NoRouteToHostException() }, { SocketException("Network is unreachable") }).reason)
        val mixed = reasonOf({ SocketTimeoutException() }, { UnknownHostException("x") }, { IOException("y") })
        assertEquals(FailureReason.ALL_SOURCES_FAILED, mixed.reason)
        assertEquals(listOf("s0", "s1", "s2"), mixed.failures.map { it.source })
        assertEquals(
            listOf(FailureReason.TIMEOUT, FailureReason.HOST_NOT_FOUND, FailureReason.CONNECTION_FAILED),
            mixed.failures.map { it.reason },
        )
        assertSame(mixed.failures.first(), mixed.cause)
        assertNull(mixed.source)
        assertTrue(mixed.message!!.contains("s0=TIMEOUT"))
    }

    @Test
    fun `왕복 상한을 넘은 결과는 ROUND_TRIP_TOO_LONG 이고 정확히 상한이면 받는다`() = runBlocking {
        val clock = builder(mono, wall, okSource(mono, roundTrip = 1_500)).build()
        assertEquals(SERVER_TIME, clock.sync().epochMillis)
        val e = assertThrowsSuspend<TimeSyncException> { builder(mono, wall, okSource(mono, roundTrip = 1_501)).build().sync() }
        assertEquals(FailureReason.ROUND_TRIP_TOO_LONG, e.failures.single().reason)
    }

    @Test
    fun `받아들일 범위 밖 서버 시각은 IMPLAUSIBLE_TIME — 경계는 받는다`() = runBlocking {
        val min = Instant.parse("2025-01-01T00:00:00Z").toEpochMilli()
        val max = Instant.parse("2200-01-01T00:00:00Z").toEpochMilli()
        listOf(min, max).forEach { t -> builder(mono, wall, okSource(mono, serverTime = { t })).build().sync() }
        listOf(min - 1, max + 1, 0L, -1L).forEach { t ->
            val e = assertThrowsSuspend<TimeSyncException> { builder(mono, wall, okSource(mono, serverTime = { t })).build().sync() }
            assertEquals(FailureReason.IMPLAUSIBLE_TIME, e.failures.single().reason)
        }
    }

    @Test
    fun `기준점이 미래인 결과는 INVALID_RESPONSE`() = runBlocking {
        val source = ScriptedSource("a") { TimeSample(SERVER_TIME, mono.elapsedMillis() + 1, 0, 0) }
        val e = assertThrowsSuspend<TimeSyncException> { builder(mono, wall, source).build().sync() }
        assertEquals(FailureReason.INVALID_RESPONSE, e.failures.single().reason)
    }

    @Test
    fun `끼운 출처의 예외 분류와 원인 보존`() = runBlocking {
        val runtime = IllegalStateException("고장")
        val e = assertThrowsSuspend<TimeSyncException> { builder(mono, wall, failingSource("a") { runtime }).build().sync() }
        val f = e.failures.single()
        assertEquals(FailureReason.SOURCE_ERROR, f.reason)
        assertSame(runtime, f.cause)
        assertEquals("a", f.source)

        // 출처 이름 없이 던진 TimeSyncException 은 이름을 붙여 다시 싸고 원래 예외를 cause 로 둔다
        val bare = TimeSyncException(FailureReason.HTTP_DATE_INVALID, "형식")
        val wrapped = assertThrowsSuspend<TimeSyncException> { builder(mono, wall, failingSource("b") { bare }).build().sync() }
            .failures.single()
        assertEquals(FailureReason.HTTP_DATE_INVALID, wrapped.reason)
        assertEquals("b", wrapped.source)
        assertSame(bare, wrapped.cause)

        // 이름이 있으면 그대로
        val named = TimeSyncException(FailureReason.KISS_OF_DEATH, "c: KoD", null, "c")
        assertSame(named, assertThrowsSuspend<TimeSyncException> { builder(mono, wall, failingSource("c") { named }).build().sync() }.failures.single())
    }

    @Test
    fun `NetworkMonitor 가 연결 없음이면 조회하지 않고 NO_NETWORK — 대기도 걸지 않는다`() = runBlocking {
        var online = false
        val source = okSource(mono)
        val clock = builder(mono, wall, source).networkMonitor { online }.build()
        val reading = clock.now()
        assertTrue(reading.isDeviceFallback)
        assertEquals(FailureReason.NO_NETWORK, reading.failure!!.reason)
        assertTrue(reading.failure!!.failures.isEmpty())
        assertEquals(0, source.calls.get())
        online = true
        assertFalse(clock.now().isDeviceFallback)
        assertEquals(1, source.calls.get())
    }

    @Test
    fun `자동 재시도는 30초부터 두 배씩 기다리고 sync 는 대기를 무시한다`() = runBlocking {
        var fail = true
        val source = ScriptedSource("a") {
            if (fail) throw SocketTimeoutException()
            TimeSample(SERVER_TIME, mono.elapsedMillis(), 10, 10)
        }
        val clock = builder(mono, wall, source).build()
        clock.now()
        assertEquals(1, source.calls.get())
        mono.advance(29_999)
        val waiting = clock.now()
        assertTrue(waiting.isDeviceFallback)
        assertEquals(FailureReason.TIMEOUT, waiting.failure!!.reason)
        assertEquals(1, source.calls.get())
        mono.advance(1)
        clock.now()
        assertEquals(2, source.calls.get())
        mono.advance(59_999)
        clock.now()
        assertEquals(2, source.calls.get())
        // 명시적 sync 는 대기와 무관하다
        assertThrowsSuspend<TimeSyncException> { clock.sync() }
        assertEquals(3, source.calls.get())
        fail = false
        assertFalse(clock.sync().isDeviceFallback)
        // 성공하면 대기가 풀린다
        clock.invalidate()
        assertFalse(clock.now().isDeviceFallback)
    }

    @Test
    fun `대기 계산 — 상한 30분, 0 이하 실패는 0`() {
        val f = net.plumt.time.internal.DefaultTrustedClock
        assertEquals(0L, f.backoffMillis(0))
        assertEquals(0L, f.backoffMillis(-3))
        assertEquals(30_000L, f.backoffMillis(1))
        assertEquals(60_000L, f.backoffMillis(2))
        assertEquals(960_000L, f.backoffMillis(6))
        assertEquals(1_800_000L, f.backoffMillis(7))
        assertEquals(1_800_000L, f.backoffMillis(Int.MAX_VALUE))
        assertEquals(0L, f.driftMillis(0))
        assertEquals(1L, f.driftMillis(1))
        assertEquals(1L, f.driftMillis(10_000))
        assertEquals(2L, f.driftMillis(10_001))
    }

    @Test
    fun `KoD 를 보낸 출처는 1시간 쉬고 다음 출처로 넘어간다`() = runBlocking {
        val kod = failingSource("kod") { TimeSyncException(FailureReason.KISS_OF_DEATH, "kod: RATE", null, "kod") }
        val backup = okSource(mono, name = "backup")
        val clock = builder(mono, wall, kod, backup).build()
        assertEquals("backup", clock.sync().sourceName)
        assertEquals(1, kod.calls.get())
        clock.sync()
        assertEquals(1, kod.calls.get())
        mono.advance(60 * 60_000L)
        clock.sync()
        assertEquals(2, kod.calls.get())
    }

    @Test
    fun `쉬는 출처만 남으면 KISS_OF_DEATH 로 실패한다`() = runBlocking {
        val kod = failingSource("kod") { TimeSyncException(FailureReason.KISS_OF_DEATH, "kod: DENY", null, "kod") }
        val clock = builder(mono, wall, kod).build()
        assertEquals(FailureReason.KISS_OF_DEATH, assertThrowsSuspend<TimeSyncException> { clock.sync() }.reason)
        val skipped = assertThrowsSuspend<TimeSyncException> { clock.sync() }
        assertEquals(FailureReason.KISS_OF_DEATH, skipped.reason)
        assertTrue(skipped.failures.single().message!!.contains("더 묻지 않는다"))
        assertEquals(1, kod.calls.get())
        clock.invalidate()
        assertThrowsSuspend<TimeSyncException> { clock.sync() }
        assertEquals(2, kod.calls.get())
    }

    @Test
    fun `peek 은 네트워크를 건드리지 않는다`() = runBlocking {
        val source = okSource(mono)
        val clock = builder(mono, wall, source).build()
        assertNull(clock.peek())
        assertEquals(0, source.calls.get())
        clock.sync()
        mono.advance(5)
        val peeked = clock.peek()!!
        assertEquals(SERVER_TIME + 5, peeked.epochMillis)
        assertFalse(peeked.isStale)
        assertNull(peeked.failure)
    }

    @Test
    fun `invalidate 는 오프셋과 저장소를 지운다`() = runBlocking {
        val store = SyncStateStore.inMemory()
        val clock = builder(mono, wall, okSource(mono)).store(store).build()
        clock.sync()
        assertNotNull(store.load())
        clock.invalidate()
        assertNull(store.load())
        assertNull(clock.peek())
    }

    @Test
    fun `저장소가 던진 예외는 감싸지 않고 전파한다`() = runBlocking {
        val boom = IllegalStateException("디스크")
        val store = object : SyncStateStore {
            override fun load(): SyncSnapshot? = null
            override fun save(snapshot: SyncSnapshot) = throw boom
            override fun clear() = Unit
        }
        val clock = builder(mono, wall, okSource(mono)).store(store).build()
        assertSame(boom, assertThrowsSuspend<IllegalStateException> { clock.sync() })
    }

    @Test
    fun `sync 성공 결과는 출처 origin 을 따른다`() = runBlocking {
        val clock = builder(mono, wall, okSource(mono, origin = TimeOrigin.NTP)).build()
        assertEquals(TimeOrigin.NTP, clock.sync().origin)
    }
}
