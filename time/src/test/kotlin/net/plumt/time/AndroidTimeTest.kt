package net.plumt.time

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.SystemClock
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import android.os.Looper
import net.plumt.time.internal.BootCountReader
import net.plumt.time.internal.ConnectivityNetworkMonitor
import net.plumt.time.internal.ElapsedRealtimeClock
import net.plumt.time.internal.PreferencesSyncStateStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetworkCapabilities
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23, 28, 34])
class AndroidTimeTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val serverTime = 1_793_707_200_000L // 2026-11-03T12:00:00Z

    private inner class CountingSource : TimeSource {
        val calls = AtomicInteger()
        override val name = "fake"
        override val origin = TimeOrigin.NTP
        override fun query(request: TimeQuery): TimeSourceResult {
            calls.incrementAndGet()
            return TimeSourceResult.Success(TimeSample(serverTime, request.monotonicClock.elapsedMillis(), 10, 10))
        }
    }

    private fun setBootCount(count: Int) {
        Settings.Global.putInt(context.contentResolver, Settings.Global.BOOT_COUNT, count)
    }

    private val sdk get() = android.os.Build.VERSION.SDK_INT

    private fun monotonic() = ElapsedRealtimeClock(BootCountReader(context.contentResolver))

    private fun clockWith(source: TimeSource, policy: FallbackPolicy = FallbackPolicy.DeviceClock) = TrustedClock.create(
        context,
        TimeConfig.builder().sources(listOf(source)).fallbackPolicy(policy).dispatcher(Dispatchers.Unconfined).build(),
    )

    private fun idleMain() = shadowOf(Looper.getMainLooper()).idle()

    @Before
    fun reset() {
        SyncStateStore.sharedPreferences(context).clear()
        // Robolectric 의 기본 네트워크에는 인터넷 능력이 없다 — 연결된 상태로 맞춘다
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = ShadowNetworkCapabilities.newInstance()
        shadowOf(caps).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        shadowOf(cm).setNetworkCapabilities(cm.activeNetwork, caps)
    }

    @Test
    fun `단조 시계는 elapsedRealtime 이고 기기 시계 변경과 무관하다`() {
        val clock = monotonic()
        val before = clock.elapsedMillis()
        assertEquals(SystemClock.elapsedRealtime(), before)
        SystemClock.setCurrentTimeMillis(0)
        ShadowSystemClock.advanceBy(Duration.ofMillis(1_234))
        assertEquals(before + 1_234, clock.elapsedMillis())
    }

    @Test
    fun `부팅 ID — API 24 이상은 BOOT_COUNT, 23 은 null`() {
        setBootCount(7)
        val id = monotonic().bootId
        if (sdk >= 24) assertEquals("boot-count:7", id) else assertNull(id)
    }

    @Test
    fun `부팅 ID — BOOT_COUNT 가 없으면 null`() {
        assertNull(monotonic().bootId)
    }

    @Test
    fun `연결 상태 — 인터넷 능력이 있으면 true, 없거나 네트워크가 없으면 false`() {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork!!
        val monitor = ConnectivityNetworkMonitor(cm)

        val withInternet = ShadowNetworkCapabilities.newInstance()
        shadowOf(withInternet).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        shadowOf(cm).setNetworkCapabilities(network, withInternet)
        assertTrue(monitor.isNetworkAvailable())

        shadowOf(cm).setNetworkCapabilities(network, ShadowNetworkCapabilities.newInstance())
        assertFalse(monitor.isNetworkAvailable())

        shadowOf(cm).setNetworkCapabilities(network, null)
        assertFalse(monitor.isNetworkAvailable())

        shadowOf(cm).setDefaultNetworkActive(false)
        shadowOf(cm).setActiveNetworkInfo(null)
        assertFalse(monitor.isNetworkAvailable())
    }

    @Test
    fun `연결 관리자가 없으면 알 수 없으니 true`() {
        assertTrue(ConnectivityNetworkMonitor(null).isNetworkAvailable())
    }

    @Test
    fun `저장소 이름 검사`() {
        assertTrue(runCatching { SyncStateStore.sharedPreferences(context, " ") }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(runCatching { SyncStateStore.sharedPreferences(context, "a/b") }.exceptionOrNull() is IllegalArgumentException)
        SyncStateStore.sharedPreferences(context, "custom").clear()
        assertEquals("net.plumt.time.sync", PreferencesSyncStateStore.DEFAULT_NAME)
    }

    @Test
    fun `프로세스 재시작 — 같은 부팅이면 조회 없이 되살리고 재부팅이면 버린다`() = runBlocking {
        setBootCount(3)
        val first = CountingSource()
        clockWith(first).sync().orThrow()
        assertEquals(1, first.calls.get())

        // 새 프로세스(새 시계 객체), 같은 부팅
        ShadowSystemClock.advanceBy(Duration.ofMillis(60_000))
        val second = CountingSource()
        val restored = clockWith(second)
        if (sdk >= 24) {
            val reading = restored.now().orThrow()
            assertEquals(serverTime + 60_000, reading.epochMillis)
            assertEquals(0, second.calls.get())
        } else {
            // API 23 은 부팅을 식별할 수 없어 되살리지 않는다
            assertNull(restored.peek())
        }

        // 재부팅: 부팅 횟수가 바뀌었다
        setBootCount(4)
        val third = CountingSource()
        val rebooted = clockWith(third)
        assertNull(rebooted.peek())
        rebooted.now()
        assertEquals(1, third.calls.get())
    }

    @Test
    fun `SharedPreferences 저장소 왕복과 지우기`() {
        val store = SyncStateStore.sharedPreferences(context)
        assertNull(store.load())
        val snapshot = SyncSnapshot(serverTime, 42, 7, TimeOrigin.HTTP_DATE, "https://x.example", "boot-count:1")
        store.save(snapshot)
        assertEquals(snapshot, SyncStateStore.sharedPreferences(context).load())
        val noBoot = SyncSnapshot(serverTime, 42, 7, TimeOrigin.CUSTOM, "c", null)
        store.save(noBoot)
        assertEquals(noBoot, store.load())
        store.clear()
        assertNull(store.load())
    }

    @Test
    fun `깨진 저장 값은 지우고 null`() {
        val prefs = context.getSharedPreferences(PreferencesSyncStateStore.DEFAULT_NAME, Context.MODE_PRIVATE)
        val store = SyncStateStore.sharedPreferences(context)
        val good = SyncSnapshot(serverTime, 42, 7, TimeOrigin.NTP, "n", "b")
        val corruptions: List<(android.content.SharedPreferences.Editor) -> Unit> = listOf(
            { it.putInt("v", 3) },
            { it.putString("origin", "device") },
            { it.putString("origin", "NTP") },
            { it.putString("origin", "MOON") },
            { it.putString("origin", "DEVICE_CLOCK") },
            { it.remove("origin") },
            { it.remove("source") },
            { it.remove("server") },
            { it.remove("anchor") },
            { it.remove("uncertainty") },
            { it.putLong("uncertainty", -5) },
            { it.remove("server").putString("server", "abc") },
            { it.remove("v").putString("v", "1") },
        )
        corruptions.forEachIndexed { i, corrupt ->
            store.save(good)
            prefs.edit().also(corrupt).commit()
            assertNull("손상 $i", store.load())
            assertTrue("손상 $i 뒤 지운다", prefs.all.isEmpty())
        }
    }

    @Test
    fun `버전 1 형식(열거형 이름)을 읽고 버전 2 로 다시 쓴다`() {
        val prefs = context.getSharedPreferences(PreferencesSyncStateStore.DEFAULT_NAME, Context.MODE_PRIVATE)
        prefs.edit().putInt("v", 1).putLong("server", serverTime).putLong("anchor", 42).putLong("uncertainty", 7)
            .putString("origin", "HTTP_DATE").putString("source", "https://x.example").putString("boot", "boot-count:1").commit()
        val loaded = SyncStateStore.sharedPreferences(context).load()
        assertEquals(SyncSnapshot(serverTime, 42, 7, TimeOrigin.HTTP_DATE, "https://x.example", "boot-count:1"), loaded)
        assertEquals(2, prefs.getInt("v", -1))
        assertEquals("http-date", prefs.getString("origin", null))
        assertEquals(loaded, SyncStateStore.sharedPreferences(context).load())
        // 버전 1 에 DEVICE_CLOCK·모르는 이름이 있으면 버린다
        prefs.edit().putInt("v", 1).putString("origin", "DEVICE_CLOCK").commit()
        assertNull(SyncStateStore.sharedPreferences(context).load())
    }

    @Test
    fun `기본 create — 한국 기본값, 저장소가 비면 peek 은 null`() {
        val clock = TrustedClock.create(context)
        assertEquals("Asia/Seoul", clock.zoneId)
        assertNull(clock.peek())
    }

    @Test
    fun `콜백 형태 — 메인 스레드에서 한 번 부른다`() {
        val clock = clockWith(CountingSource())
        val results = mutableListOf<TimeResult>()
        var onMain = false
        val handle = clock.sync { onMain = Looper.myLooper() == Looper.getMainLooper(); results += it }
        idleMain()
        assertTrue(onMain)
        assertEquals(serverTime, results.single().orThrow().epochMillis)
        assertFalse(handle.isActive)
        handle.cancel()
        val second = clock.now { results += it }
        idleMain()
        assertEquals(2, results.size)
        assertTrue(second.toString().contains("active=false"))
    }

    @Test
    fun `콜백 형태 — 실패도 결과로 오고 취소하면 부르지 않는다`() {
        val failing = object : TimeSource {
            override val name = "down"
            override val origin = TimeOrigin.CUSTOM
            override fun query(request: TimeQuery): TimeSourceResult = TimeSourceResult.Failure(FailureReason.TIMEOUT, "늦다")
        }
        val clock = clockWith(failing, FallbackPolicy.None)
        val results = mutableListOf<TimeResult>()
        clock.now { results += it }
        idleMain()
        assertEquals(FailureReason.TIMEOUT, (results.single() as TimeResult.Failure).failure.reason)

        val cancelled = clock.sync { results += it }
        assertTrue(cancelled.isActive)
        cancelled.cancel()
        idleMain()
        assertFalse(cancelled.isActive)
        assertEquals(1, results.size)
    }
}
