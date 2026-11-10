package net.plumt.time

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** 프로세스 재시작(저장소에서 되살리기)과 재부팅 판정. */
class PersistenceTest {

    private val store = SyncStateStore.inMemory()

    private fun clock(mono: FakeMonotonic, source: TimeSource, maxAge: Long = 24 * 3_600_000L) =
        builder(mono, FakeWall(), source).store(store).refreshIntervalMillis(minOf(maxAge, 3_600_000L)).maxAgeMillis(maxAge).build()

    @Test
    fun `같은 부팅이면 새 프로세스가 조회 없이 오프셋을 되살린다`() = runBlocking {
        val mono = FakeMonotonic(now = 10_000, bootId = "b1")
        clock(mono, okSource(mono)).sync()
        val saved = store.load()!!
        assertEquals("b1", saved.bootId)
        assertEquals(10_000L, saved.anchorElapsedMillis)

        // 프로세스 재시작: 시계 객체를 새로 만든다. 단조 시계는 이어진다
        mono.advance(3_600_000)
        val source = okSource(mono)
        val restored = clock(mono, source).now()
        assertEquals(SERVER_TIME + 3_600_000, restored.epochMillis)
        assertEquals(0, source.calls.get())
    }

    @Test
    fun `부팅 ID 가 다르면 재부팅으로 보고 버린다`() = runBlocking {
        val mono = FakeMonotonic(now = 10_000, bootId = "b1")
        clock(mono, okSource(mono)).sync()
        val rebooted = FakeMonotonic(now = 50_000, bootId = "b2")
        val source = okSource(rebooted)
        val c = clock(rebooted, source)
        assertNull(c.peek())
        assertNull(store.load())
        c.now()
        assertEquals(1, source.calls.get())
    }

    @Test
    fun `부팅 ID 를 모르면 되살리지 않는다`() = runBlocking {
        val mono = FakeMonotonic(now = 10_000, bootId = null)
        clock(mono, okSource(mono)).sync()
        assertNull(store.load()!!.bootId)
        assertNull(clock(mono, okSource(mono)).peek())
        assertNull(store.load())
    }

    @Test
    fun `같은 부팅 ID 인데 단조 시계가 기준점보다 작으면 버린다`() = runBlocking {
        val mono = FakeMonotonic(now = 10_000, bootId = "b1")
        clock(mono, okSource(mono)).sync()
        val back = FakeMonotonic(now = 9_999, bootId = "b1")
        assertNull(clock(back, okSource(back)).peek())
        assertNull(store.load())
    }

    @Test
    fun `되살린 값이 최대 보관 기간을 넘었으면 버린다 — 정확히 같으면 쓴다`() = runBlocking {
        val mono = FakeMonotonic(now = 10_000, bootId = "b1")
        clock(mono, okSource(mono), maxAge = 60_000).sync()
        mono.advance(60_000)
        assertNotNull(clock(mono, okSource(mono), maxAge = 60_000).peek())
        mono.advance(1)
        assertNull(clock(mono, okSource(mono), maxAge = 60_000).peek())
        assertNull(store.load())
    }

    @Test
    fun `같은 프로세스에서 단조 시계가 거꾸로 가면 재부팅으로 보고 지운다`() = runBlocking {
        val mono = FakeMonotonic(now = 10_000, bootId = "b1")
        val c = clock(mono, okSource(mono))
        c.sync()
        mono.now = 5_000
        assertNull(c.peek())
        assertNull(store.load())
    }

    @Test
    fun `저장소가 비었으면 그냥 동기화한다`() = runBlocking {
        val mono = FakeMonotonic()
        val source = okSource(mono)
        assertNull(clock(mono, source).peek())
        assertEquals(0, source.calls.get())
    }

    @Test
    fun `JVM 기본 단조 시계는 프로세스마다 부팅 ID 가 달라 다음 프로세스에서 되살리지 않는다`() {
        val id = net.plumt.time.internal.SystemMonotonicClock.bootId!!
        org.junit.Assert.assertTrue(id.startsWith("jvm-"))
        val a = net.plumt.time.internal.SystemMonotonicClock.elapsedMillis()
        val b = net.plumt.time.internal.SystemMonotonicClock.elapsedMillis()
        org.junit.Assert.assertTrue(b >= a && a >= 0)
    }
}
