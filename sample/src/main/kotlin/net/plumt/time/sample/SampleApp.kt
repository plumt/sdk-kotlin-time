package net.plumt.time.sample

import android.app.Application
import net.plumt.time.FallbackPolicy
import net.plumt.time.SyncStateStore
import net.plumt.time.TimeConfig
import net.plumt.time.TrustedClock

/** 시계는 앱 전체에서 하나만 만들어 들고 다닌다. */
class SampleApp : Application() {
    lateinit var clock: TrustedClock
        private set

    /** 기기 시계를 쓰지 않는 판정용 시계(저장소 이름을 나눈다). */
    lateinit var strictClock: TrustedClock
        private set

    /** NTP 호스트가 없는 시계 — UDP 123 이 막힌 망처럼 HTTP Date 폴백을 탄다(저장소 이름을 나눈다). */
    lateinit var httpFallbackClock: TrustedClock
        private set

    override fun onCreate() {
        super.onCreate()
        clock = TrustedClock.create(this)
        strictClock = TrustedClock.create(
            this,
            TimeConfig.builder()
                .fallbackPolicy(FallbackPolicy.None)
                .store(SyncStateStore.sharedPreferences(this, "sample.strict"))
                .build(),
        )
        httpFallbackClock = TrustedClock.create(
            this,
            TimeConfig.builder()
                .ntpServers(listOf("ntp.invalid"))
                .store(SyncStateStore.sharedPreferences(this, "sample.http"))
                .build(),
        )
    }
}
