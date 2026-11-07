package net.plumt.time

/**
 * 지금 유효한 동기화가 없고 새 동기화도 실패했을 때 [TrustedClock.now] 가 무엇을 돌려줄지.
 *
 * "유효" 는 마지막 동기화 뒤 경과가 갱신 주기 안쪽이라는 뜻이다. 갱신 주기는 지났지만 최대 보관 기간 안쪽인
 * 오프셋은 **지난 동기화**(stale)라 부른다 — 같은 부팅의 단조 시계로 이어 세므로 기기 시계보다 훨씬 믿을 만하다.
 */
public enum class FallbackPolicy {
    /** 지난 동기화 → 기기 시계 순서로 물러난다(기본). 결과의 [TimeReading.isDeviceFallback] 로 알 수 있다. */
    DEVICE_CLOCK,

    /** 지난 동기화까지만 쓰고 기기 시계는 쓰지 않는다. 둘 다 없으면 [TimeSyncException] 을 던진다. */
    LAST_SYNC_ONLY,

    /** 물러나지 않는다. 유효한 동기화가 없고 새 동기화도 실패하면 [TimeSyncException] 을 던진다. */
    NONE,
}
