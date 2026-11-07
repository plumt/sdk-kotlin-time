package net.plumt.time

import net.plumt.time.internal.SystemMonotonicClock

/**
 * 단조 증가 시계. 오프셋의 기준점을 이 시계로 잰다 — 사용자가 기기 시계를 바꿔도 이 값은 튀지 않는다.
 *
 * JVM 기본값([system])은 `System.nanoTime` 이다. Android 는 기기가 잠든 동안에도 흐르는 `elapsedRealtime` 을
 * 써야 하므로 `time-android` 의 구현을 쓴다(`nanoTime` 은 깊은 잠에서 멈춘다).
 */
public interface MonotonicClock {
    /** 단조 증가 밀리초. 원점은 임의다. */
    public fun elapsedMillis(): Long

    /**
     * 같은 부팅(같은 시계 원점) 동안 변하지 않는 식별자. 저장된 오프셋을 다음 프로세스에서 되살릴 때 이 값이
     * 같아야 한다. 알 수 없으면 `null` — 그러면 저장된 오프셋을 되살리지 않는다.
     */
    public val bootId: String?

    public companion object {
        /** `System.nanoTime` 기반. 원점이 JVM 프로세스마다 달라 [bootId] 도 프로세스마다 새로 만든다. */
        @JvmStatic
        public fun system(): MonotonicClock = SystemMonotonicClock
    }
}

/** 기기 벽시계. 기기 시계 폴백과 [TimeReading.deviceClockOffsetMillis] 계산에만 쓴다. */
public fun interface WallClock {
    /** 기기 벽시계의 epoch 밀리초. */
    public fun currentTimeMillis(): Long

    public companion object {
        /** `System.currentTimeMillis`. */
        @JvmStatic
        public fun system(): WallClock = WallClock { System.currentTimeMillis() }
    }
}

/** 조회 전에 연결 여부를 본다. `false` 면 네트워크를 건드리지 않고 [FailureReason.NO_NETWORK] 로 끝낸다. */
public fun interface NetworkMonitor {
    /** 지금 인터넷에 닿을 수 있어 보이면 `true`. 모르면 `true` 를 돌려 조회해 보게 한다. */
    public fun isNetworkAvailable(): Boolean

    public companion object {
        /** 늘 `true` — 판단을 실제 조회에 맡긴다(JVM 기본값). */
        @JvmStatic
        public fun assumeAvailable(): NetworkMonitor = NetworkMonitor { true }
    }
}
