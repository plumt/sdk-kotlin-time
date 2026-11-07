package net.plumt.time.android.internal

import android.content.ContentResolver
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import net.plumt.time.MonotonicClock
import net.plumt.time.NetworkMonitor
import net.plumt.time.SyncSnapshot
import net.plumt.time.SyncStateStore
import net.plumt.time.TimeOrigin

/** `Settings.Global.BOOT_COUNT` 읽기(API 24+). 없거나 못 읽으면 `null`. */
internal class BootCountReader(private val resolver: ContentResolver) {
    fun read(): Int? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return null
        val count = Settings.Global.getInt(resolver, Settings.Global.BOOT_COUNT, -1)
        return count.takeIf { it >= 0 }
    }
}

internal class ElapsedRealtimeClock(private val bootCount: BootCountReader) : MonotonicClock {
    override fun elapsedMillis(): Long = SystemClock.elapsedRealtime()

    /** 부팅 횟수는 프로세스가 사는 동안 바뀌지 않으므로 한 번만 읽는다. */
    override val bootId: String? by lazy { bootCount.read()?.let { "boot-count:$it" } }
}

internal class ConnectivityNetworkMonitor(private val manager: ConnectivityManager?) : NetworkMonitor {
    override fun isNetworkAvailable(): Boolean {
        val cm = manager ?: return true
        return try {
            val network = cm.activeNetwork ?: return false
            val capabilities = cm.getNetworkCapabilities(network) ?: return false
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } catch (_: SecurityException) {
            // ACCESS_NETWORK_STATE 가 지워졌다 — 연결 여부를 알 수 없으니 실제 조회에 맡긴다
            true
        }
    }
}

/**
 * 원시 값 키로 저장한다. 형식 버전이 다르거나 값이 깨졌으면 지우고 `null` — 캐시라 잃어도 다음 동기화가 채운다.
 */
internal class PreferencesSyncStateStore(private val prefs: SharedPreferences) : SyncStateStore {

    override fun load(): SyncSnapshot? {
        if (!prefs.contains(KEY_VERSION)) return null
        val snapshot = read()
        if (snapshot == null) clear()
        return snapshot
    }

    private fun read(): SyncSnapshot? = try {
        if (prefs.getInt(KEY_VERSION, -1) != VERSION) {
            null
        } else {
            val origin = TimeOrigin.values().firstOrNull { it.name == prefs.getString(KEY_ORIGIN, null) }
                ?.takeIf { it != TimeOrigin.DEVICE_CLOCK }
            val source = prefs.getString(KEY_SOURCE, null)
            if (origin == null || source == null || !hasNumbers()) {
                null
            } else {
                SyncSnapshot(
                    serverEpochMillis = prefs.getLong(KEY_SERVER, 0),
                    anchorElapsedMillis = prefs.getLong(KEY_ANCHOR, 0),
                    uncertaintyMillis = prefs.getLong(KEY_UNCERTAINTY, -1),
                    origin = origin,
                    sourceName = source,
                    bootId = prefs.getString(KEY_BOOT, null),
                )
            }
        }
    } catch (_: ClassCastException) {
        null // 같은 키에 다른 타입이 들어 있다
    } catch (_: IllegalArgumentException) {
        null // 음수 오차 등 SyncSnapshot 이 거부한 값
    }

    private fun hasNumbers() = prefs.contains(KEY_SERVER) && prefs.contains(KEY_ANCHOR) && prefs.contains(KEY_UNCERTAINTY)

    override fun save(snapshot: SyncSnapshot) {
        prefs.edit()
            .putInt(KEY_VERSION, VERSION)
            .putLong(KEY_SERVER, snapshot.serverEpochMillis)
            .putLong(KEY_ANCHOR, snapshot.anchorElapsedMillis)
            .putLong(KEY_UNCERTAINTY, snapshot.uncertaintyMillis)
            .putString(KEY_ORIGIN, snapshot.origin.name)
            .putString(KEY_SOURCE, snapshot.sourceName)
            .putString(KEY_BOOT, snapshot.bootId)
            .apply()
    }

    override fun clear() {
        prefs.edit().clear().apply()
    }

    private companion object {
        const val VERSION = 1
        const val KEY_VERSION = "v"
        const val KEY_SERVER = "server"
        const val KEY_ANCHOR = "anchor"
        const val KEY_UNCERTAINTY = "uncertainty"
        const val KEY_ORIGIN = "origin"
        const val KEY_SOURCE = "source"
        const val KEY_BOOT = "boot"
    }
}
