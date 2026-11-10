package net.plumt.time.internal

import android.annotation.SuppressLint
import android.os.Build
import androidx.annotation.RequiresApi
import android.content.Context
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import net.plumt.time.FailureReason
import net.plumt.time.FallbackPolicy
import net.plumt.time.MonotonicClock
import net.plumt.time.NetworkMonitor
import net.plumt.time.SyncSnapshot
import net.plumt.time.SyncStateStore
import net.plumt.time.TimeOrigin
import net.plumt.time.TimeCallHandle
import net.plumt.time.TimeCallback
import net.plumt.time.TimeConfig
import net.plumt.time.TimeFailure
import net.plumt.time.TimeQuery
import net.plumt.time.TimeReading
import net.plumt.time.TimeResult
import net.plumt.time.TimeSample
import net.plumt.time.TimeSource
import net.plumt.time.TimeSourceResult
import net.plumt.time.TrustedClock
import net.plumt.time.WallClock
import java.time.Clock
import java.time.DateTimeException
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.coroutineContext

internal class DefaultTrustedClock(private val config: ClockConfig) : TrustedClock {

    override val zoneId: String = config.zoneId

    private val mono = config.monotonicClock
    private val mutex = Mutex()
    private val loadLock = Any()

    @Volatile private var state: SyncSnapshot? = null

    @Volatile private var loaded = false

    /** 끝난 동기화 횟수. 기다리던 호출이 자기가 부른 뒤 끝난 동기화의 결과를 나눠 받는 데 쓴다. */
    @Volatile private var generation = 0L

    @Volatile private var lastOutcome: Outcome? = null

    @Volatile private var lastFailure: SyncError? = null

    @Volatile private var consecutiveFailures = 0

    @Volatile private var nextRetryAtElapsed = 0L

    /** 진행 중인 동기화를 기다리는 호출 수(시험이 동시 호출을 맞추는 데 쓴다). */
    internal val waitingCallers = AtomicInteger()

    /** KoD 를 보낸 출처 이름 → 다시 물어도 되는 단조 시각. */
    private val silenced = ConcurrentHashMap<String, Long>()

    /** 콜백 형태 호출이 도는 범위. 인스턴스가 사는 동안 유지한다(앱 전체에서 하나만 만든다). */
    private val callbackScope = CoroutineScope(SupervisorJob() + config.callbackDispatcher)

    override suspend fun now(): TimeResult {
        val now = mono.elapsedMillis()
        val current = validState(now)
        if (current != null && now - current.anchorElapsedMillis <= config.refreshIntervalMillis) {
            return TimeResult.Success(reading(current, now, stale = false, failure = null))
        }
        val failure = lastFailure
        if (failure != null && consecutiveFailures > 0 && now < nextRetryAtElapsed) return fallback(failure)
        return when (val outcome = syncShared()) {
            is Outcome.Success -> TimeResult.Success(reading(outcome.snapshot, mono.elapsedMillis(), stale = false, failure = null))
            is Outcome.Failure -> fallback(outcome.error)
        }
    }

    override suspend fun sync(): TimeResult = when (val outcome = syncShared()) {
        is Outcome.Success -> TimeResult.Success(reading(outcome.snapshot, mono.elapsedMillis(), stale = false, failure = null))
        is Outcome.Failure -> TimeResult.Failure(outcome.error.failure)
    }

    override fun now(callback: TimeCallback): TimeCallHandle = callbackScope.launchCallback({ now() }, callback)

    override fun sync(callback: TimeCallback): TimeCallHandle = callbackScope.launchCallback({ sync() }, callback)

    override fun peek(): TimeReading? {
        val now = mono.elapsedMillis()
        val current = validState(now) ?: return null
        val stale = now - current.anchorElapsedMillis > config.refreshIntervalMillis
        return reading(current, now, stale, if (stale) lastFailure?.failure else null)
    }

    override fun invalidate() {
        synchronized(loadLock) {
            state = null
            loaded = true
            config.store.clear()
        }
        lastFailure = null
        consecutiveFailures = 0
        nextRetryAtElapsed = 0L
        silenced.clear()
    }

    @SuppressLint("NewApi") // java.time: API 26 또는 앱의 desugaring — README "API 레벨별 동작"
    override fun asJavaClock(): Clock = TrustedJavaClock(this, ZoneId.of(zoneId))

    /** 네트워크를 기다리지 않는 현재 시각(`java.time.Clock` 용). */
    @SuppressLint("NewApi") // java.time: API 26 또는 앱의 desugaring — README "API 레벨별 동작"
    internal fun nonBlockingMillis(): Long {
        val reading = peek()
        val policy = config.fallbackPolicy
        if (reading != null && (!reading.isStale || policy != FallbackPolicy.None)) return reading.epochMillis
        if (policy == FallbackPolicy.DeviceClock) return config.wallClock.currentTimeMillis()
        throw DateTimeException(
            "쓸 수 있는 동기화가 없고 정책($policy)이 기기 시계를 막는다 — 먼저 sync() 를 부른다",
        )
    }

    private suspend fun syncShared(): Outcome {
        val seen = generation
        waitingCallers.incrementAndGet()
        try {
            mutex.lock()
        } finally {
            waitingCallers.decrementAndGet()
        }
        try {
            val shared = lastOutcome
            if (generation != seen && shared != null) return shared
            val outcome = try {
                val (sample, source) = withContext(config.dispatcher) { querySources() }
                val snapshot = SyncSnapshot(
                    serverEpochMillis = sample.serverEpochMillis,
                    anchorElapsedMillis = sample.anchorElapsedMillis,
                    uncertaintyMillis = sample.uncertaintyMillis,
                    origin = source.origin,
                    sourceName = source.name,
                    bootId = mono.bootId,
                )
                config.store.save(snapshot)
                synchronized(loadLock) {
                    state = snapshot
                    loaded = true
                }
                lastFailure = null
                consecutiveFailures = 0
                nextRetryAtElapsed = 0L
                Outcome.Success(snapshot)
            } catch (e: SyncError) {
                recordFailure(e)
                Outcome.Failure(e)
            }
            lastOutcome = outcome
            generation += 1
            return outcome
        } finally {
            mutex.unlock()
        }
    }

    private fun recordFailure(error: SyncError) {
        lastFailure = error
        // 연결이 없다는 신호만으로 끝난 실패는 네트워크를 건드리지 않았으니 대기를 걸지 않는다 — 연결이 돌아오면 바로 시도한다
        if (error.reason == FailureReason.NO_NETWORK && error.failures.isEmpty() && error.source == null) return
        consecutiveFailures += 1
        nextRetryAtElapsed = mono.elapsedMillis() + backoffMillis(consecutiveFailures)
    }

    private suspend fun querySources(): Pair<TimeSample, TimeSource> {
        if (!config.networkMonitor.isNetworkAvailable()) {
            throw SyncError(FailureReason.NO_NETWORK, "네트워크에 연결돼 있지 않다")
        }
        val failures = ArrayList<SyncError>()
        for (source in config.sources) {
            coroutineContext.ensureActive()
            val now = mono.elapsedMillis()
            val until = silenced[source.name]
            if (until != null && now < until) {
                failures += SyncError(
                    FailureReason.KISS_OF_DEATH,
                    "${source.name}: 직전 Kiss-o'-Death 로 ${until - now}ms 더 묻지 않는다",
                    null,
                    source.name,
                )
                continue
            }
            val result = attempt(source)
            if (result is TimeSample) return result to source
            val failure = result as SyncError
            if (failure.reason == FailureReason.KISS_OF_DEATH) silenced[source.name] = mono.elapsedMillis() + KOD_SILENCE_MILLIS
            failures += failure
        }
        throw Failures.aggregate(failures)
    }

    /** 출처 하나를 묻고 검사한다. 성공이면 [TimeSample], 실패면 [SyncError]. */
    @Suppress("TooGenericExceptionCaught")
    private fun attempt(source: TimeSource): Any {
        val name = source.name
        val sample = try {
            val query = TimeQuery(config.timeoutMillis, mono)
            if (source is SampleSource) {
                source.sample(query)
            } else {
                when (val result = source.query(query)) {
                    is TimeSourceResult.Success -> result.sample
                    is TimeSourceResult.Failure -> return SyncError(result.reason, "$name: ${result.message}", result.cause, name)
                }
            }
        } catch (e: Exception) {
            // 끼운 구현의 모든 예외를 원인 보존 실패로 바꾼다 — 한 출처의 오류가 다음 출처로의 폴백을 끊지 않게 한다
            return Failures.classify(e, name)
        }
        return when {
            sample.roundTripMillis > config.maxRoundTripMillis -> SyncError(
                FailureReason.ROUND_TRIP_TOO_LONG,
                "$name: 왕복 ${sample.roundTripMillis}ms 가 상한 ${config.maxRoundTripMillis}ms 를 넘는다",
                null,
                name,
            )
            sample.serverEpochMillis !in MIN_VALID_EPOCH_MILLIS..MAX_VALID_EPOCH_MILLIS -> SyncError(
                FailureReason.IMPLAUSIBLE_TIME,
                "$name: 서버 시각 ${sample.serverEpochMillis} 이 받아들일 범위($MIN_VALID_EPOCH_MILLIS..$MAX_VALID_EPOCH_MILLIS) 밖이다",
                null,
                name,
            )
            sample.anchorElapsedMillis > mono.elapsedMillis() -> SyncError(
                FailureReason.INVALID_RESPONSE,
                "$name: 기준점이 지금 단조 시계보다 미래다",
                null,
                name,
            )
            else -> sample
        }
    }

    private fun fallback(error: SyncError): TimeResult {
        val now = mono.elapsedMillis()
        val stale = validState(now)
        val failure = error.failure
        val (useStale, useDevice) = when (config.fallbackPolicy) {
            FallbackPolicy.DeviceClock -> true to true
            FallbackPolicy.LastSyncOnly -> true to false
            FallbackPolicy.None -> false to false
        }
        if (stale != null && useStale) return TimeResult.Success(reading(stale, now, stale = true, failure = failure))
        if (useDevice) {
            val device = TimeReading(
                epochMillis = config.wallClock.currentTimeMillis(),
                origin = TimeOrigin.DEVICE_CLOCK,
                uncertaintyMillis = null,
                sourceName = null,
                ageMillis = 0,
                isStale = false,
                deviceClockOffsetMillis = 0,
                failure = failure,
                zoneId = zoneId,
            )
            return TimeResult.Success(device)
        }
        return TimeResult.Failure(failure)
    }

    /** 쓸 수 있는 오프셋(갱신 주기를 넘겼어도 최대 보관 기간 안쪽). 처음 부를 때 저장소에서 되살린다. */
    private fun validState(now: Long): SyncSnapshot? {
        if (!loaded) restore(now)
        val current = state ?: return null
        val age = now - current.anchorElapsedMillis
        if (age < 0) {
            // 단조 시계가 기준점보다 작다 — 재부팅으로 원점이 리셋됐다
            synchronized(loadLock) {
                if (state === current) {
                    state = null
                    config.store.clear()
                }
            }
            return null
        }
        return if (age <= config.maxAgeMillis) current else null
    }

    private fun restore(now: Long) {
        synchronized(loadLock) {
            if (loaded) return
            loaded = true
            val saved = config.store.load() ?: return
            val boot = mono.bootId
            val age = now - saved.anchorElapsedMillis
            if (boot != null && saved.bootId == boot && age in 0..config.maxAgeMillis) {
                state = saved
            } else {
                // 다른 부팅(또는 부팅을 알 수 없음)에서 잰 기준점은 이 단조 시계와 이어지지 않는다
                config.store.clear()
            }
        }
    }

    private fun reading(snapshot: SyncSnapshot, now: Long, stale: Boolean, failure: TimeFailure?): TimeReading {
        val age = (now - snapshot.anchorElapsedMillis).coerceAtLeast(0)
        val epoch = snapshot.serverEpochMillis + age
        return TimeReading(
            epochMillis = epoch,
            origin = snapshot.origin,
            uncertaintyMillis = snapshot.uncertaintyMillis + driftMillis(age),
            sourceName = snapshot.sourceName,
            ageMillis = age,
            isStale = stale,
            deviceClockOffsetMillis = epoch - config.wallClock.currentTimeMillis(),
            failure = failure,
            zoneId = zoneId,
        )
    }

    private sealed class Outcome {
        class Success(val snapshot: SyncSnapshot) : Outcome()
        class Failure(val error: SyncError) : Outcome()
    }

    internal companion object {
        /** 공개 진입점 [TrustedClock.create] 의 구현. 정하지 않은 결합 지점에 Android 구현을 끼운다. */
        fun create(context: Context, config: TimeConfig): DefaultTrustedClock {
            val app = context.applicationContext ?: context
            return create(
                config = config,
                monotonicClock = config.monotonicClock ?: ElapsedRealtimeClock(BootCountReader(app.contentResolver)),
                networkMonitor = config.networkMonitor ?: ConnectivityNetworkMonitor(
                    app.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager?,
                ),
                store = config.store ?: PreferencesSyncStateStore(
                    app.getSharedPreferences(PreferencesSyncStateStore.DEFAULT_NAME, Context.MODE_PRIVATE),
                ),
                callbackDispatcher = Dispatchers.Main,
            )
        }

        /** 결합 지점을 모두 받아 만든다(시험은 Android 없이 이 경로를 쓴다). */
        fun create(
            config: TimeConfig,
            monotonicClock: MonotonicClock,
            networkMonitor: NetworkMonitor,
            store: SyncStateStore,
            callbackDispatcher: CoroutineDispatcher,
        ): DefaultTrustedClock = DefaultTrustedClock(
            ClockConfig(
                sources = config.resolvedSources,
                zoneId = config.zoneId,
                timeoutMillis = config.timeoutMillis,
                maxRoundTripMillis = config.maxRoundTripMillis,
                refreshIntervalMillis = config.refreshIntervalMillis,
                maxAgeMillis = config.maxAgeMillis,
                fallbackPolicy = config.fallbackPolicy,
                monotonicClock = monotonicClock,
                wallClock = config.wallClock ?: WallClock.system(),
                networkMonitor = networkMonitor,
                store = store,
                dispatcher = config.dispatcher,
                callbackDispatcher = callbackDispatcher,
            ),
        )

        /** 2025-01-01T00:00:00Z. 이보다 이른 서버 시각은 손상·오류로 본다. */
        const val MIN_VALID_EPOCH_MILLIS = 1_735_689_600_000L

        /** 2200-01-01T00:00:00Z. */
        const val MAX_VALID_EPOCH_MILLIS = 7_258_118_400_000L

        /** 단조 시계 주파수 편차 가정(100ppm). 경과 시간에 곱해 오차 추정에 더한다. */
        const val DRIFT_PPM = 100L
        private const val PPM = 1_000_000L

        const val MIN_BACKOFF_MILLIS = 30_000L
        const val MAX_BACKOFF_MILLIS = 30L * 60_000L
        private const val MAX_SHIFT = 16

        /** KoD 를 받은 출처를 쉬게 하는 시간. */
        const val KOD_SILENCE_MILLIS = 60L * 60_000L

        fun driftMillis(age: Long): Long = (age * DRIFT_PPM + PPM - 1) / PPM

        /** n 번째 연속 실패 뒤 자동 재시도 대기: 30초 × 2^(n-1), 상한 30분. */
        fun backoffMillis(failures: Int): Long {
            if (failures <= 0) return 0L
            val shift = (failures - 1).coerceAtMost(MAX_SHIFT)
            return (MIN_BACKOFF_MILLIS shl shift).coerceAtMost(MAX_BACKOFF_MILLIS)
        }
    }
}

/** 네트워크를 기다리지 않는 `java.time.Clock` 어댑터. */
@RequiresApi(Build.VERSION_CODES.O)
internal class TrustedJavaClock(private val clock: DefaultTrustedClock, private val zone: ZoneId) : Clock() {
    override fun getZone(): ZoneId = zone

    override fun withZone(zone: ZoneId): Clock = if (zone == this.zone) this else TrustedJavaClock(clock, zone)

    override fun millis(): Long = clock.nonBlockingMillis()

    override fun instant(): java.time.Instant = java.time.Instant.ofEpochMilli(millis())

    override fun equals(other: Any?): Boolean = other is TrustedJavaClock && other.clock === clock && other.zone == zone

    override fun hashCode(): Int = System.identityHashCode(clock) * 31 + zone.hashCode()

    override fun toString(): String = "TrustedJavaClock[$zone]"
}
