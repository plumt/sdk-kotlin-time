package net.plumt.time

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import net.plumt.time.internal.DefaultTrustedClock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.SocketTimeoutException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ConcurrencyTest {

    private val mono = FakeMonotonic()

    /** 들어오면 [entered] 를 열고 [release] 가 열릴 때까지 막히는 출처. */
    private class GateSource(private val mono: FakeMonotonic, private val fail: Boolean = false) : TimeSource {
        override val name = "gate"
        override val origin = TimeOrigin.NTP
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        @Volatile var calls = 0
        override fun query(request: TimeQuery): TimeSample {
            calls++
            entered.countDown()
            check(release.await(10, TimeUnit.SECONDS)) { "풀리지 않았다" }
            if (fail) throw SocketTimeoutException("늦다")
            return TimeSample(SERVER_TIME, mono.elapsedMillis(), 5, 5)
        }
    }

    private fun clockOf(source: TimeSource, policy: FallbackPolicy = FallbackPolicy.DEVICE_CLOCK) =
        TrustedClock.builder().monotonicClock(mono).wallClock(FakeWall()).sources(listOf(source))
            .fallbackPolicy(policy).dispatcher(Dispatchers.IO).build() as DefaultTrustedClock

    private suspend fun awaitWaiting(clock: DefaultTrustedClock, count: Int) {
        withTimeout(10_000) {
            while (clock.waitingCallers.get() < count) yield()
        }
    }

    @Test
    fun `동시 sync 10개는 조회 한 번을 나눠 받는다`() = runBlocking(Dispatchers.Default) {
        val source = GateSource(mono)
        val clock = clockOf(source)
        val leader = async { clock.sync() }
        assertTrue(source.entered.await(10, TimeUnit.SECONDS))
        val followers = (1..9).map { async { clock.sync() } }
        awaitWaiting(clock, 9)
        source.release.countDown()
        val all = (listOf(leader) + followers).awaitAll()
        assertEquals(1, source.calls)
        assertTrue(all.all { it.epochMillis == SERVER_TIME && it.origin == TimeOrigin.NTP })
    }

    @Test
    fun `동시 now 100개도 조회 한 번`() = runBlocking(Dispatchers.Default) {
        val source = GateSource(mono)
        val clock = clockOf(source)
        val leader = async { clock.now() }
        assertTrue(source.entered.await(10, TimeUnit.SECONDS))
        val followers = (1..99).map { async { clock.now() } }
        awaitWaiting(clock, 99)
        source.release.countDown()
        (listOf(leader) + followers).awaitAll().forEach { assertEquals(SERVER_TIME, it.epochMillis) }
        assertEquals(1, source.calls)
    }

    @Test
    fun `동시 호출이 실패를 나눠 받는다 — sync 는 같은 예외, now 는 폴백`() = runBlocking(Dispatchers.Default) {
        val source = GateSource(mono, fail = true)
        val clock = clockOf(source)
        val leader = async { runCatching { clock.sync() } }
        assertTrue(source.entered.await(10, TimeUnit.SECONDS))
        val syncFollower = async { runCatching { clock.sync() } }
        val nowFollower = async { clock.now() }
        awaitWaiting(clock, 2)
        source.release.countDown()
        val a = leader.await().exceptionOrNull() as TimeSyncException
        val b = syncFollower.await().exceptionOrNull() as TimeSyncException
        assertTrue(a === b)
        assertEquals(FailureReason.TIMEOUT, a.reason)
        val fallback = nowFollower.await()
        assertTrue(fallback.isDeviceFallback)
        assertTrue(fallback.failure === a)
        assertEquals(1, source.calls)
    }

    @Test
    fun `조회 중 취소되면 결과를 버리고 잠금을 푼다 — 다음 호출이 다시 조회한다`() = runBlocking(Dispatchers.Default) {
        val source = GateSource(mono)
        val clock = clockOf(source)
        val job = launch { clock.sync() }
        assertTrue(source.entered.await(10, TimeUnit.SECONDS))
        job.cancel()
        source.release.countDown()
        job.join()
        assertTrue(job.isCancelled)
        assertNull(clock.peek())
        val again = okSource(mono)
        // 잠금이 풀렸는지: 같은 시계로 다시 부르면 끝난다(GateSource 는 이미 열려 있다)
        assertEquals(SERVER_TIME, withTimeout(10_000) { clock.sync() }.epochMillis)
        assertEquals(2, source.calls)
        assertEquals(0, again.calls.get())
    }

    @Test
    fun `기다리던 호출이 취소돼도 대기 계수가 돌아온다`() = runBlocking(Dispatchers.Default) {
        val source = GateSource(mono)
        val clock = clockOf(source)
        val leader = async { clock.sync() }
        assertTrue(source.entered.await(10, TimeUnit.SECONDS))
        val waiter = launch { clock.sync() }
        awaitWaiting(clock, 1)
        waiter.cancel()
        waiter.join()
        assertEquals(0, clock.waitingCallers.get())
        source.release.countDown()
        assertEquals(SERVER_TIME, leader.await().epochMillis)
    }

    @Test
    fun `출처 사이에서 취소를 확인한다`() = runBlocking(Dispatchers.Default) {
        val started = CountDownLatch(1)
        lateinit var job: kotlinx.coroutines.Job
        val first = ScriptedSource("first") {
            started.countDown()
            job.cancel()
            throw SocketTimeoutException()
        }
        val second = okSource(mono, name = "second")
        val clock = TrustedClock.builder().monotonicClock(mono).sources(listOf(first, second))
            .dispatcher(Dispatchers.IO).build()
        job = launch {
            try {
                clock.sync()
            } catch (e: CancellationException) {
                throw e
            }
        }
        job.join()
        assertTrue(job.isCancelled)
        assertEquals(0, second.calls.get())
    }
}
