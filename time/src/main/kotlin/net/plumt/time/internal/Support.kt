package net.plumt.time.internal

import kotlinx.coroutines.CoroutineDispatcher
import net.plumt.time.FallbackPolicy
import net.plumt.time.MonotonicClock
import net.plumt.time.NetworkMonitor
import net.plumt.time.SyncSnapshot
import net.plumt.time.SyncStateStore
import net.plumt.time.TimeSource
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
)

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
