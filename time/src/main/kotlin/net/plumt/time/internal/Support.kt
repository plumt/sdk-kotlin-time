package net.plumt.time.internal

import kotlinx.coroutines.CoroutineDispatcher
import net.plumt.time.FallbackPolicy
import net.plumt.time.MonotonicClock
import net.plumt.time.NetworkMonitor
import net.plumt.time.SyncSnapshot
import net.plumt.time.SyncStateStore
import net.plumt.time.TimeQuery
import net.plumt.time.TimeSample
import net.plumt.time.TimeSource
import net.plumt.time.TimeSourceResult
import net.plumt.time.WallClock
import java.util.UUID

internal class ClockConfig(
    val sources: List<TimeSource>,
    val zoneId: String,
    val timeoutMillis: Int,
    val maxRoundTripMillis: Long,
    val refreshIntervalMillis: Long,
    val maxAgeMillis: Long,
    val fallbackPolicy: FallbackPolicy,
    val monotonicClock: MonotonicClock,
    val wallClock: WallClock,
    val networkMonitor: NetworkMonitor,
    val store: SyncStateStore,
    val dispatcher: CoroutineDispatcher,
    /** 콜백을 부를 디스패처. 운영은 `Dispatchers.Main`, 시험은 바꿔 끼운다. */
    val callbackDispatcher: CoroutineDispatcher,
)

/**
 * 라이브러리 내장 출처. 실패를 [SyncError] 로 던지는 [sample] 을 라이브러리가 바로 부르고,
 * 공개 [query] 는 같은 실패를 [TimeSourceResult.Failure] 로 바꿔 돌려준다(앱이 내장 출처를 감쌀 때).
 */
internal abstract class SampleSource : TimeSource {
    /** 서버 시각을 조회한다. 블로킹. 실패는 [SyncError] 나 입출력 예외로 던진다. */
    abstract fun sample(request: TimeQuery): TimeSample

    @Suppress("TooGenericExceptionCaught")
    final override fun query(request: TimeQuery): TimeSourceResult = try {
        TimeSourceResult.Success(sample(request))
    } catch (e: Exception) {
        val error = Failures.classify(e, name)
        TimeSourceResult.Failure(error.reason, error.message ?: error.reason.name, error.cause)
    }
}

/** `System.nanoTime` 을 이 클래스가 처음 쓰인 순간 기준 밀리초로. 원점이 프로세스마다 달라 부팅 ID 도 프로세스마다 새로 만든다. */
internal object SystemMonotonicClock : MonotonicClock {
    private val origin = System.nanoTime()
    private const val NANOS_PER_MILLI = 1_000_000L

    override fun elapsedMillis(): Long = (System.nanoTime() - origin) / NANOS_PER_MILLI

    override val bootId: String = "jvm-" + UUID.randomUUID()
}

internal class InMemorySyncStateStore : SyncStateStore {
    @Volatile
    private var value: SyncSnapshot? = null

    override fun load(): SyncSnapshot? = value

    override fun save(snapshot: SyncSnapshot) {
        value = snapshot
    }

    override fun clear() {
        value = null
    }
}
