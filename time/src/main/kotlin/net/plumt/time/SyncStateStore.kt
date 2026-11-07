package net.plumt.time

import net.plumt.time.internal.InMemorySyncStateStore

/**
 * 마지막으로 맞춘 오프셋 한 벌. 서버 시각과 그 순간의 단조 시계 값을 한 쌍으로 둔다 —
 * 지금 시각 = [serverEpochMillis] + (지금 단조 시계 − [anchorElapsedMillis]).
 *
 * @property serverEpochMillis 기준점 순간의 서버 시각
 * @property anchorElapsedMillis 기준점의 단조 시계 값
 * @property uncertaintyMillis 기준점 순간의 오차 추정
 * @property origin 얻은 출처([TimeOrigin.DEVICE_CLOCK] 제외)
 * @property sourceName 출처 이름
 * @property bootId 기준점을 잰 부팅의 식별자([MonotonicClock.bootId])
 */
public class SyncSnapshot(
    public val serverEpochMillis: Long,
    public val anchorElapsedMillis: Long,
    public val uncertaintyMillis: Long,
    public val origin: TimeOrigin,
    public val sourceName: String,
    public val bootId: String?,
) {
    init {
        require(uncertaintyMillis >= 0) { "uncertaintyMillis 는 0 이상이어야 한다: $uncertaintyMillis" }
        require(origin != TimeOrigin.DEVICE_CLOCK) { "origin 에 DEVICE_CLOCK 은 올 수 없다" }
    }

    override fun equals(other: Any?): Boolean = other is SyncSnapshot &&
        serverEpochMillis == other.serverEpochMillis &&
        anchorElapsedMillis == other.anchorElapsedMillis &&
        uncertaintyMillis == other.uncertaintyMillis &&
        origin == other.origin &&
        sourceName == other.sourceName &&
        bootId == other.bootId

    override fun hashCode(): Int {
        var h = serverEpochMillis.hashCode()
        h = 31 * h + anchorElapsedMillis.hashCode()
        h = 31 * h + uncertaintyMillis.hashCode()
        h = 31 * h + origin.hashCode()
        h = 31 * h + sourceName.hashCode()
        h = 31 * h + (bootId?.hashCode() ?: 0)
        return h
    }

    override fun toString(): String =
        "SyncSnapshot(server=$serverEpochMillis, anchor=$anchorElapsedMillis, ±$uncertaintyMillis ms, $origin $sourceName, boot=$bootId)"
}

/**
 * 오프셋 저장소. 프로세스가 다시 떠도 같은 부팅이면 동기화 없이 정확한 시각을 내려고 둔다.
 * 기본값([inMemory])은 프로세스 메모리에만 둔다. Android 는 `time-android` 의 SharedPreferences 저장소를 쓴다.
 *
 * 라이브러리는 되살린 값의 [SyncSnapshot.bootId]·기준점·경과를 다시 검사하므로 저장소는 값만 보관하면 된다.
 * 저장소가 던진 예외는 감싸지 않고 호출부로 전파된다.
 */
public interface SyncStateStore {
    /** 저장된 값. 없으면 `null`. */
    public fun load(): SyncSnapshot?

    /** 값을 덮어쓴다. */
    public fun save(snapshot: SyncSnapshot)

    /** 값을 지운다. */
    public fun clear()

    public companion object {
        /** 프로세스 메모리에만 두는 저장소. */
        @JvmStatic
        public fun inMemory(): SyncStateStore = InMemorySyncStateStore()
    }
}
