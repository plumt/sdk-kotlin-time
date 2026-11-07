package net.plumt.time.android

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.SystemClock
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import net.plumt.time.SyncSnapshot
import net.plumt.time.TimeOrigin
import net.plumt.time.TimeQuery
import net.plumt.time.TimeSample
import net.plumt.time.TimeSource
import net.plumt.time.android.internal.ConnectivityNetworkMonitor
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
        override fun query(request: TimeQuery): TimeSample {
            calls.incrementAndGet()
            return TimeSample(serverTime, request.monotonicClock.elapsedMillis(), 10, 10)
        }
    }

    private fun setBootCount(count: Int) {
        Settings.Global.putInt(context.contentResolver, Settings.Global.BOOT_COUNT, count)
    }

    private val sdk get() = android.os.Build.VERSION.SDK_INT

    @Before
    fun reset() {
        AndroidTime.preferencesStore(context).clear()
        // Robolectric 의 기본 네트워크에는 인터넷 능력이 없다 — 연결된 상태로 맞춘다
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = ShadowNetworkCapabilities.newInstance()
        shadowOf(caps).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        shadowOf(cm).setNetworkCapabilities(cm.activeNetwork, caps)
    }

    @Test
    fun `단조 시계는 elapsedRealtime 이고 기기 시계 변경과 무관하다`() {
        val clock = AndroidTime.monotonicClock(context)
        val before = clock.elapsedMillis()
        assertEquals(SystemClock.elapsedRealtime(), before)
        SystemClock.setCurrentTimeMillis(0)
        ShadowSystemClock.advanceBy(Duration.ofMillis(1_234))
        assertEquals(before + 1_234, clock.elapsedMillis())
    }

    @Test
    fun `부팅 ID — API 24 이상은 BOOT_COUNT, 23 은 null`() {
        setBootCount(7)
        val id = AndroidTime.monotonicClock(context).bootId
        if (sdk >= 24) assertEquals("boot-count:7", id) else assertNull(id)
    }

    @Test
    fun `부팅 ID — BOOT_COUNT 가 없으면 null`() {
        assertNull(AndroidTime.monotonicClock(context).bootId)
    }

    @Test
    fun `연결 상태 — 인터넷 능력이 있으면 true, 없거나 네트워크가 없으면 false`() {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork!!
        val monitor = AndroidTime.networkMonitor(context)

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
        assertTrue(runCatching { AndroidTime.preferencesStore(context, " ") }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(runCatching { AndroidTime.preferencesStore(context, "a/b") }.exceptionOrNull() is IllegalArgumentException)
        AndroidTime.preferencesStore(context, "custom").clear()
        assertEquals("net.plumt.time.sync", AndroidTime.DEFAULT_STORE_NAME)
    }

    @Test
    fun `프로세스 재시작 — 같은 부팅이면 조회 없이 되살리고 재부팅이면 버린다`() = runBlocking {
        setBootCount(3)
        val first = CountingSource()
        AndroidTime.clockBuilder(context).sources(listOf(first)).dispatcher(Dispatchers.Unconfined).build().sync()
        assertEquals(1, first.calls.get())

        // 새 프로세스(새 시계 객체), 같은 부팅
        ShadowSystemClock.advanceBy(Duration.ofMillis(60_000))
        val second = CountingSource()
        val restored = AndroidTime.clockBuilder(context).sources(listOf(second)).dispatcher(Dispatchers.Unconfined).build()
        if (sdk >= 24) {
            val reading = restored.now()
            assertEquals(serverTime + 60_000, reading.epochMillis)
            assertEquals(0, second.calls.get())
        } else {
            // API 23 은 부팅을 식별할 수 없어 되살리지 않는다
            assertNull(restored.peek())
        }

        // 재부팅: 부팅 횟수가 바뀌었다
        setBootCount(4)
        val third = CountingSource()
        val rebooted = AndroidTime.clockBuilder(context).sources(listOf(third)).dispatcher(Dispatchers.Unconfined).build()
        assertNull(rebooted.peek())
        rebooted.now()
        assertEquals(1, third.calls.get())
    }

    @Test
    fun `SharedPreferences 저장소 왕복과 지우기`() {
        val store = AndroidTime.preferencesStore(context)
        assertNull(store.load())
        val snapshot = SyncSnapshot(serverTime, 42, 7, TimeOrigin.HTTP_DATE, "https://x.example", "boot-count:1")
        store.save(snapshot)
        assertEquals(snapshot, AndroidTime.preferencesStore(context).load())
        val noBoot = SyncSnapshot(serverTime, 42, 7, TimeOrigin.CUSTOM, "c", null)
        store.save(noBoot)
        assertEquals(noBoot, store.load())
        store.clear()
        assertNull(store.load())
    }

    @Test
    fun `깨진 저장 값은 지우고 null`() {
        val prefs = context.getSharedPreferences(AndroidTime.DEFAULT_STORE_NAME, Context.MODE_PRIVATE)
        val store = AndroidTime.preferencesStore(context)
        val good = SyncSnapshot(serverTime, 42, 7, TimeOrigin.NTP, "n", "b")
        val corruptions: List<(android.content.SharedPreferences.Editor) -> Unit> = listOf(
            { it.putInt("v", 2) },
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
}
