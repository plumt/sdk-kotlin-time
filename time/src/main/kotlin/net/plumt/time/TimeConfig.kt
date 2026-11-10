package net.plumt.time

import android.annotation.SuppressLint
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import java.time.ZoneId
import java.util.TimeZone

/**
 * [TrustedClock] 설정. 불변이다 — [builder] 로 만들고 [toBuilder] 로 고친 사본을 만든다.
 *
 * 기본값: 한국 서버([TimeServers.KOREA_NTP]·[TimeServers.KOREA_HTTP]), 시간대 `Asia/Seoul`, 출처당 대기 3초,
 * 왕복 상한 1.5초, 갱신 주기 6시간, 최대 보관 24시간, [FallbackPolicy.DeviceClock], 조회 디스패처 `Dispatchers.IO`.
 * 단조 시계·벽시계·연결 판단·저장소는 정하지 않으면 [TrustedClock.create] 가 Android 구현을 끼운다.
 */
public class TimeConfig internal constructor(builder: Builder) {
    /** NTP 서버 호스트 이름(포트 123). [sources] 를 정했으면 쓰지 않는다. */
    public val ntpServers: List<String> = builder.ntpServers
    /** NTP 가 모두 실패하면 시도할 HTTPS `Date` URL. [sources] 를 정했으면 쓰지 않는다. */
    public val httpDateUrls: List<String> = builder.httpUrls
    /** 출처 목록을 통째로 바꾼 값. 정하지 않았으면 `null`. */
    public val sources: List<TimeSource>? = builder.customSources
    /** 결과의 기본 시간대 ID. */
    public val zoneId: String = builder.zoneId
    /** 출처 하나의 응답 대기 상한(밀리초). */
    public val timeoutMillis: Int = builder.timeoutMillis
    /** 받아들일 왕복 지연 상한(밀리초). */
    public val maxRoundTripMillis: Long = builder.maxRoundTripMillis
    /** 이 시간이 지나면 [TrustedClock.now] 가 다시 동기화한다(밀리초). */
    public val refreshIntervalMillis: Long = builder.refreshIntervalMillis
    /** 지난 동기화를 물러날 곳으로 쓸 수 있는 최대 경과(밀리초). */
    public val maxAgeMillis: Long = builder.maxAgeMillis
    /** 동기화 실패 시 물러날 곳. */
    public val fallbackPolicy: FallbackPolicy = builder.fallbackPolicy
    /** 기준점을 잴 단조 시계. `null` 이면 `SystemClock.elapsedRealtime` + 부팅 횟수. */
    public val monotonicClock: MonotonicClock? = builder.monotonicClock
    /** 기기 벽시계. `null` 이면 `System.currentTimeMillis`. */
    public val wallClock: WallClock? = builder.wallClock
    /** 조회 전 연결 판단. `null` 이면 `ConnectivityManager`. */
    public val networkMonitor: NetworkMonitor? = builder.networkMonitor
    /** 오프셋 저장소. `null` 이면 [SyncStateStore.sharedPreferences] 기본 이름. */
    public val store: SyncStateStore? = builder.store
    /** 블로킹 조회를 돌릴 디스패처. */
    public val dispatcher: CoroutineDispatcher = builder.dispatcher

    /** 실제로 시도할 출처(정한 [sources], 아니면 NTP → HTTP 순서). */
    internal val resolvedSources: List<TimeSource> =
        sources ?: (ntpServers.map { TimeSources.ntp(it) } + httpDateUrls.map { TimeSources.httpDate(it) })

    /** 이 값에서 시작하는 빌더. */
    public fun toBuilder(): Builder = Builder().also { b ->
        b.ntpServers = ntpServers
        b.httpUrls = httpDateUrls
        b.customSources = sources
        b.zoneId = zoneId
        b.timeoutMillis = timeoutMillis
        b.maxRoundTripMillis = maxRoundTripMillis
        b.refreshIntervalMillis = refreshIntervalMillis
        b.maxAgeMillis = maxAgeMillis
        b.fallbackPolicy = fallbackPolicy
        b.monotonicClock = monotonicClock
        b.wallClock = wallClock
        b.networkMonitor = networkMonitor
        b.store = store
        b.dispatcher = dispatcher
    }

    override fun toString(): String =
        "TimeConfig(sources=${resolvedSources.map { it.name }}, zone=$zoneId, timeout=$timeoutMillis, " +
            "maxRoundTrip=$maxRoundTripMillis, refresh=$refreshIntervalMillis, maxAge=$maxAgeMillis, policy=$fallbackPolicy)"

    /** 빌더를 만든다. */
    public companion object {
        /** 기본값에서 시작하는 빌더. */
        @JvmStatic
        public fun builder(): Builder = Builder()
    }

    /** [TimeConfig] 빌더. 각 설정 함수는 틀린 값에 바로 `IllegalArgumentException` 을 던진다. */
    public class Builder internal constructor() {
        internal var ntpServers: List<String> = TimeServers.KOREA_NTP
        internal var httpUrls: List<String> = TimeServers.KOREA_HTTP
        internal var customSources: List<TimeSource>? = null
        internal var zoneId: String = Limits.DEFAULT_ZONE_ID
        internal var timeoutMillis: Int = Limits.DEFAULT_TIMEOUT_MILLIS
        internal var maxRoundTripMillis: Long = Limits.DEFAULT_MAX_ROUND_TRIP_MILLIS
        internal var refreshIntervalMillis: Long = Limits.DEFAULT_REFRESH_INTERVAL_MILLIS
        internal var maxAgeMillis: Long = Limits.DEFAULT_MAX_AGE_MILLIS
        internal var fallbackPolicy: FallbackPolicy = FallbackPolicy.DeviceClock
        internal var monotonicClock: MonotonicClock? = null
        internal var wallClock: WallClock? = null
        internal var networkMonitor: NetworkMonitor? = null
        internal var store: SyncStateStore? = null
        internal var dispatcher: CoroutineDispatcher = Dispatchers.IO

        /** NTP 서버(호스트 이름, 포트 123)를 앞에서부터 시도한다. 빈 목록이면 NTP 를 쓰지 않는다. 기본 [TimeServers.KOREA_NTP]. */
        public fun ntpServers(hosts: List<String>): Builder = apply {
            hosts.forEach { TimeSources.ntp(it) }
            ntpServers = immutableCopy(hosts)
        }

        /** NTP 가 모두 실패하면 시도할 HTTPS `Date` URL. 빈 목록이면 HTTP 를 쓰지 않는다. 기본 [TimeServers.KOREA_HTTP]. */
        public fun httpDateUrls(urls: List<String>): Builder = apply {
            urls.forEach { TimeSources.httpDate(it) }
            httpUrls = immutableCopy(urls)
        }

        /**
         * 출처 목록을 통째로 바꾼다. 설정하면 [ntpServers]·[httpDateUrls] 는 쓰지 않는다.
         *
         * @throws IllegalArgumentException 목록이 비었거나 [TimeOrigin.DEVICE_CLOCK] 출처가 있을 때
         */
        public fun sources(sources: List<TimeSource>): Builder = apply {
            require(sources.isNotEmpty()) { "sources 가 비었다" }
            require(sources.none { it.origin == TimeOrigin.DEVICE_CLOCK }) {
                "DEVICE_CLOCK 출처는 끼울 수 없다 — 기기 시계 폴백은 fallbackPolicy 로 정한다"
            }
            customSources = immutableCopy(sources)
        }

        /** 결과의 기본 시간대. 기본 `Asia/Seoul`. 서버 선택과 무관하다. API 26 미만은 desugaring 이 필요하다. */
        @SuppressLint("NewApi") // java.time: API 26 또는 앱의 desugaring — README "API 레벨별 동작"
        public fun zone(zone: ZoneId): Builder = apply { zoneId = zone.id }

        /**
         * 결과의 기본 시간대를 ID 로 준다(`java.time` 없이 쓸 수 있다). `java.util.TimeZone` 이 아는 지역 ID 만 받는다.
         *
         * @throws IllegalArgumentException 모르는 ID 일 때
         */
        public fun zone(zoneId: String): Builder = apply {
            require(zoneId in TimeZone.getAvailableIDs()) { "모르는 시간대 ID 다: '$zoneId' (예: Asia/Seoul, UTC)" }
            this.zoneId = zoneId
        }

        /** 출처 하나의 응답 대기 상한(밀리초). 100..60000, 기본 3000. */
        public fun timeoutMillis(millis: Int): Builder = apply {
            require(millis in Limits.MIN_TIMEOUT_MILLIS..Limits.MAX_TIMEOUT_MILLIS) {
                "timeoutMillis 는 ${Limits.MIN_TIMEOUT_MILLIS}..${Limits.MAX_TIMEOUT_MILLIS} 이어야 한다: $millis"
            }
            timeoutMillis = millis
        }

        /** 받아들일 왕복 지연 상한(밀리초). 넘으면 [FailureReason.ROUND_TRIP_TOO_LONG]. 1..60000, 기본 1500. */
        public fun maxRoundTripMillis(millis: Long): Builder = apply {
            require(millis in 1..Limits.MAX_TIMEOUT_MILLIS.toLong()) {
                "maxRoundTripMillis 는 1..${Limits.MAX_TIMEOUT_MILLIS} 이어야 한다: $millis"
            }
            maxRoundTripMillis = millis
        }

        /** 이 시간이 지나면 [TrustedClock.now] 가 다시 동기화한다(밀리초). 1초..30일, 기본 6시간. */
        public fun refreshIntervalMillis(millis: Long): Builder = apply {
            require(millis in Limits.MIN_INTERVAL_MILLIS..Limits.MAX_INTERVAL_MILLIS) {
                "refreshIntervalMillis 는 ${Limits.MIN_INTERVAL_MILLIS}..${Limits.MAX_INTERVAL_MILLIS} 이어야 한다: $millis"
            }
            refreshIntervalMillis = millis
        }

        /** 지난 동기화를 물러날 곳으로 쓸 수 있는 최대 경과(밀리초). 갱신 주기 이상..30일, 기본 24시간. */
        public fun maxAgeMillis(millis: Long): Builder = apply {
            require(millis in Limits.MIN_INTERVAL_MILLIS..Limits.MAX_INTERVAL_MILLIS) {
                "maxAgeMillis 는 ${Limits.MIN_INTERVAL_MILLIS}..${Limits.MAX_INTERVAL_MILLIS} 이어야 한다: $millis"
            }
            maxAgeMillis = millis
        }

        /** 동기화 실패 시 물러날 곳. 기본 [FallbackPolicy.DeviceClock]. */
        public fun fallbackPolicy(policy: FallbackPolicy): Builder = apply { fallbackPolicy = policy }

        /** 기준점을 잴 단조 시계. 기본은 `SystemClock.elapsedRealtime` + 부팅 횟수. */
        public fun monotonicClock(clock: MonotonicClock): Builder = apply { monotonicClock = clock }

        /** 기기 벽시계. 기본 [WallClock.system]. */
        public fun wallClock(clock: WallClock): Builder = apply { wallClock = clock }

        /** 연결 상태. 기본은 `ConnectivityManager`. 판단을 실제 조회에 맡기려면 [NetworkMonitor.assumeAvailable]. */
        public fun networkMonitor(monitor: NetworkMonitor): Builder = apply { networkMonitor = monitor }

        /** 오프셋 저장소. 기본은 [SyncStateStore.sharedPreferences] 기본 이름. */
        public fun store(store: SyncStateStore): Builder = apply { this.store = store }

        /** 블로킹 조회를 돌릴 디스패처. 기본 `Dispatchers.IO`. */
        public fun dispatcher(dispatcher: CoroutineDispatcher): Builder = apply { this.dispatcher = dispatcher }

        /**
         * @throws IllegalArgumentException 출처가 하나도 없을 때, 최대 보관 기간이 갱신 주기보다 짧을 때
         */
        public fun build(): TimeConfig {
            require(customSources != null || ntpServers.isNotEmpty() || httpUrls.isNotEmpty()) {
                "출처가 하나도 없다 — ntpServers·httpDateUrls 가 모두 비었다"
            }
            require(maxAgeMillis >= refreshIntervalMillis) {
                "maxAgeMillis($maxAgeMillis) 는 refreshIntervalMillis($refreshIntervalMillis) 이상이어야 한다"
            }
            return TimeConfig(this)
        }
    }
}

/** 설정 기본값과 경계. 공개 API 에 상수로 드러내지 않는다(값은 KDoc 에 적는다). */
internal object Limits {
    const val DEFAULT_ZONE_ID = "Asia/Seoul"
    const val DEFAULT_TIMEOUT_MILLIS = 3_000
    const val MIN_TIMEOUT_MILLIS = 100
    const val MAX_TIMEOUT_MILLIS = 60_000
    const val DEFAULT_MAX_ROUND_TRIP_MILLIS = 1_500L
    const val DEFAULT_REFRESH_INTERVAL_MILLIS = 6L * 60 * 60 * 1000
    const val DEFAULT_MAX_AGE_MILLIS = 24L * 60 * 60 * 1000
    const val MIN_INTERVAL_MILLIS = 1_000L
    const val MAX_INTERVAL_MILLIS = 30L * 24 * 60 * 60 * 1000
}

internal fun <T> immutableCopy(list: List<T>): List<T> = java.util.Collections.unmodifiableList(ArrayList(list))
