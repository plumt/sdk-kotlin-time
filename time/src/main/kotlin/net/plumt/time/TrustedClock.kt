package net.plumt.time

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import net.plumt.time.internal.ClockConfig
import net.plumt.time.internal.DefaultTrustedClock
import java.time.ZoneId
import java.util.TimeZone

/**
 * 기기 시계 조작에 흔들리지 않는 현재 시각.
 *
 * NTP → HTTP `Date` → (정책이 허락하면) 기기 시계 순서로 시각을 얻는다. 한 번 맞추면 서버 시각과 그 순간의
 * **단조 시계** 값을 한 쌍으로 보관하고, 이후에는 네트워크 없이 단조 시계 경과만 더한다 — 그래서 사용자가
 * 기기 시계를 바꿔도 [now] 가 그대로다. 갱신 주기가 지나면 [now] 가 다시 맞춘다.
 *
 * 스레드 안전하다. 동시에 여러 곳에서 [now]·[sync] 를 불러도 네트워크 조회는 한 번만 나가고 결과를 나눠 받는다.
 *
 * ```kotlin
 * val clock = TrustedClock.builder().build()       // 한국 서버·Asia/Seoul
 * val reading = clock.now()
 * reading.toLocalDateTime()                        // 서울 시각
 * ```
 */
public interface TrustedClock {
    /** 변환 함수가 인자 없이 쓰는 시간대 ID. */
    public val zoneId: String

    /**
     * 현재 시각. 유효한 오프셋이 있으면 네트워크 없이 곧바로 돌려준다. 없거나 갱신 주기가 지났으면 동기화하고,
     * 실패하면 [FallbackPolicy] 에 따라 물러난다(물러난 이유는 [TimeReading.failure]).
     *
     * 직전 자동 동기화가 실패했으면 대기(30초부터 두 배씩, 상한 30분)가 끝나기 전에는 다시 조회하지 않고 바로 물러난다.
     * 메인 스레드에서 불러도 된다 — 블로킹 조회는 [Builder.dispatcher] 에서 돈다.
     *
     * @throws TimeSyncException 정책이 물러남을 막았는데 쓸 시각이 없을 때
     */
    public suspend fun now(): TimeReading

    /**
     * 캐시와 대기를 보지 않고 지금 동기화한다. 앱 시작·네트워크 복구 시점에 부른다.
     *
     * @throws TimeSyncException 모든 출처가 실패했을 때(정책과 무관)
     */
    public suspend fun sync(): TimeReading

    /**
     * 네트워크를 기다리지 않고 보관한 오프셋으로만 계산한다. 지난 동기화(stale)도 [TimeReading.isStale] 로 표시해
     * 돌려준다. 오프셋이 없거나 최대 보관 기간을 넘었거나 재부팅으로 무효면 `null`.
     */
    public fun peek(): TimeReading?

    /** 보관한 오프셋·실패 대기·KoD 휴식을 모두 지운다. 다음 [now] 는 새로 동기화한다. */
    public fun invalidate()

    /**
     * `java.time.Clock` 으로 감싼다. 네트워크를 기다리지 않는다 — [peek] 의 값을 쓰고, 없으면 정책에 따라
     * 기기 시계를 쓰거나 [FailureReason.NOT_SYNCED] 로 [TimeSyncException] 을 던진다. 시간대는 [zoneId].
     * API 26 미만 Android 는 desugaring 이 필요하다.
     */
    public fun asJavaClock(): java.time.Clock

    public companion object {
        /** 기본값(한국 서버, `Asia/Seoul`, 기기 시계 폴백 허용)에서 시작하는 빌더. */
        @JvmStatic
        public fun builder(): Builder = Builder()
    }

    /** [TrustedClock] 설정. 각 설정 함수는 틀린 값에 바로 `IllegalArgumentException` 을 던진다. */
    public class Builder internal constructor() {
        private var ntpServers: List<String> = TimeServers.KOREA_NTP
        private var httpUrls: List<String> = TimeServers.KOREA_HTTP
        private var customSources: List<TimeSource>? = null
        private var zoneId: String = DEFAULT_ZONE_ID
        private var timeoutMillis: Int = DEFAULT_TIMEOUT_MILLIS
        private var maxRoundTripMillis: Long = DEFAULT_MAX_ROUND_TRIP_MILLIS
        private var refreshIntervalMillis: Long = DEFAULT_REFRESH_INTERVAL_MILLIS
        private var maxAgeMillis: Long = DEFAULT_MAX_AGE_MILLIS
        private var fallbackPolicy: FallbackPolicy = FallbackPolicy.DEVICE_CLOCK
        private var monotonicClock: MonotonicClock = MonotonicClock.system()
        private var wallClock: WallClock = WallClock.system()
        private var networkMonitor: NetworkMonitor = NetworkMonitor.assumeAvailable()
        private var store: SyncStateStore? = null
        private var dispatcher: CoroutineDispatcher = Dispatchers.IO

        /** NTP 서버(호스트 이름, 포트 123)를 앞에서부터 시도한다. 빈 목록이면 NTP 를 쓰지 않는다. 기본 [TimeServers.KOREA_NTP]. */
        public fun ntpServers(hosts: List<String>): Builder = apply {
            hosts.forEach { TimeSources.ntp(it) }
            ntpServers = hosts.toList()
        }

        /** NTP 가 모두 실패하면 시도할 HTTPS `Date` URL. 빈 목록이면 HTTP 를 쓰지 않는다. 기본 [TimeServers.KOREA_HTTP]. */
        public fun httpDateUrls(urls: List<String>): Builder = apply {
            urls.forEach { TimeSources.httpDate(it) }
            httpUrls = urls.toList()
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
            customSources = sources.toList()
        }

        /** 결과의 기본 시간대. 기본 `Asia/Seoul`. 서버 선택과 무관하다. */
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
            require(millis in MIN_TIMEOUT_MILLIS..MAX_TIMEOUT_MILLIS) {
                "timeoutMillis 는 $MIN_TIMEOUT_MILLIS..$MAX_TIMEOUT_MILLIS 이어야 한다: $millis"
            }
            timeoutMillis = millis
        }

        /** 받아들일 왕복 지연 상한(밀리초). 넘으면 [FailureReason.ROUND_TRIP_TOO_LONG]. 1..60000, 기본 1500. */
        public fun maxRoundTripMillis(millis: Long): Builder = apply {
            require(millis in 1..MAX_TIMEOUT_MILLIS.toLong()) { "maxRoundTripMillis 는 1..$MAX_TIMEOUT_MILLIS 이어야 한다: $millis" }
            maxRoundTripMillis = millis
        }

        /** 이 시간이 지나면 [now] 가 다시 동기화한다(밀리초). 1초..30일, 기본 6시간. */
        public fun refreshIntervalMillis(millis: Long): Builder = apply {
            require(millis in MIN_INTERVAL_MILLIS..MAX_INTERVAL_MILLIS) {
                "refreshIntervalMillis 는 $MIN_INTERVAL_MILLIS..$MAX_INTERVAL_MILLIS 이어야 한다: $millis"
            }
            refreshIntervalMillis = millis
        }

        /** 지난 동기화를 물러날 곳으로 쓸 수 있는 최대 경과(밀리초). 갱신 주기 이상..30일, 기본 24시간. */
        public fun maxAgeMillis(millis: Long): Builder = apply {
            require(millis in MIN_INTERVAL_MILLIS..MAX_INTERVAL_MILLIS) {
                "maxAgeMillis 는 $MIN_INTERVAL_MILLIS..$MAX_INTERVAL_MILLIS 이어야 한다: $millis"
            }
            maxAgeMillis = millis
        }

        /** 동기화 실패 시 물러날 곳. 기본 [FallbackPolicy.DEVICE_CLOCK]. */
        public fun fallbackPolicy(policy: FallbackPolicy): Builder = apply { fallbackPolicy = policy }

        /** 기준점을 잴 단조 시계. 기본 [MonotonicClock.system]. */
        public fun monotonicClock(clock: MonotonicClock): Builder = apply { monotonicClock = clock }

        /** 기기 벽시계. 기본 [WallClock.system]. */
        public fun wallClock(clock: WallClock): Builder = apply { wallClock = clock }

        /** 연결 상태. 기본 [NetworkMonitor.assumeAvailable]. */
        public fun networkMonitor(monitor: NetworkMonitor): Builder = apply { networkMonitor = monitor }

        /** 오프셋 저장소. 기본 [SyncStateStore.inMemory](빌드마다 새로). */
        public fun store(store: SyncStateStore): Builder = apply { this.store = store }

        /** 블로킹 조회를 돌릴 디스패처. 기본 `Dispatchers.IO`. */
        public fun dispatcher(dispatcher: CoroutineDispatcher): Builder = apply { this.dispatcher = dispatcher }

        /**
         * @throws IllegalArgumentException 출처가 하나도 없을 때, 최대 보관 기간이 갱신 주기보다 짧을 때
         */
        public fun build(): TrustedClock {
            val sources = customSources
                ?: (ntpServers.map { TimeSources.ntp(it) } + httpUrls.map { TimeSources.httpDate(it) })
            require(sources.isNotEmpty()) { "출처가 하나도 없다 — ntpServers·httpDateUrls 가 모두 비었다" }
            require(maxAgeMillis >= refreshIntervalMillis) {
                "maxAgeMillis($maxAgeMillis) 는 refreshIntervalMillis($refreshIntervalMillis) 이상이어야 한다"
            }
            return DefaultTrustedClock(
                ClockConfig(
                    sources = sources,
                    zoneId = zoneId,
                    timeoutMillis = timeoutMillis,
                    maxRoundTripMillis = maxRoundTripMillis,
                    refreshIntervalMillis = refreshIntervalMillis,
                    maxAgeMillis = maxAgeMillis,
                    fallbackPolicy = fallbackPolicy,
                    monotonicClock = monotonicClock,
                    wallClock = wallClock,
                    networkMonitor = networkMonitor,
                    store = store ?: SyncStateStore.inMemory(),
                    dispatcher = dispatcher,
                ),
            )
        }

        private companion object {
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
    }
}
