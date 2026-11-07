package net.plumt.time.android

import android.content.Context
import android.net.ConnectivityManager
import net.plumt.time.MonotonicClock
import net.plumt.time.NetworkMonitor
import net.plumt.time.SyncStateStore
import net.plumt.time.TrustedClock
import net.plumt.time.android.internal.BootCountReader
import net.plumt.time.android.internal.ConnectivityNetworkMonitor
import net.plumt.time.android.internal.ElapsedRealtimeClock
import net.plumt.time.android.internal.PreferencesSyncStateStore

/**
 * [TrustedClock] 의 Android 결합.
 *
 * ```kotlin
 * val clock = AndroidTime.clockBuilder(context).build()   // 앱 전체에서 하나만 만든다
 * ```
 */
public object AndroidTime {

    /** 저장소 파일 기본 이름. */
    public const val DEFAULT_STORE_NAME: String = "net.plumt.time.sync"

    /**
     * `SystemClock.elapsedRealtime` 단조 시계. 기기가 잠든 동안에도 흐르고 기기 시계 변경에 영향받지 않는다.
     * 부팅 ID 는 `Settings.Global.BOOT_COUNT`(API 24+)로 만든다. API 23 은 부팅을 식별할 수 없어 `null` —
     * 저장된 오프셋을 다음 프로세스에서 되살리지 않는다(같은 프로세스 안에서는 그대로 쓴다).
     */
    @JvmStatic
    public fun monotonicClock(context: Context): MonotonicClock =
        ElapsedRealtimeClock(BootCountReader(context.applicationContext.contentResolver))

    /**
     * `ConnectivityManager` 로 연결 여부를 본다. 활성 네트워크가 없거나 인터넷 능력이 없으면 `false`.
     * 연결 상태를 읽을 권한이 없으면(앱이 병합 규칙으로 권한을 지운 경우) 알 수 없으므로 `true` 를 돌려 실제 조회에 맡긴다.
     */
    @JvmStatic
    public fun networkMonitor(context: Context): NetworkMonitor {
        val manager = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager?
        return ConnectivityNetworkMonitor(manager)
    }

    /**
     * 앱 전용 SharedPreferences([name])에 오프셋을 둔다. 프로세스가 다시 떠도 같은 부팅이면 동기화 없이 쓴다.
     * 저장하는 값은 서버 시각·기준점·오차·출처 이름·부팅 ID 뿐이다(개인정보 없음).
     *
     * @throws IllegalArgumentException [name] 이 비었거나 `/` 를 담았을 때
     */
    @JvmStatic
    @JvmOverloads
    public fun preferencesStore(context: Context, name: String = DEFAULT_STORE_NAME): SyncStateStore {
        require(name.isNotBlank()) { "name 이 비었다" }
        require('/' !in name) { "name 에 '/' 를 넣지 않는다: '$name'" }
        return PreferencesSyncStateStore(context.applicationContext.getSharedPreferences(name, Context.MODE_PRIVATE))
    }

    /** 위 세 가지를 끼운 빌더. 나머지 설정(서버·시간대·정책)은 이어서 바꾼다. */
    @JvmStatic
    public fun clockBuilder(context: Context): TrustedClock.Builder = TrustedClock.builder()
        .monotonicClock(monotonicClock(context))
        .networkMonitor(networkMonitor(context))
        .store(preferencesStore(context))
}
