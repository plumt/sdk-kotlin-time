package net.plumt.time

/** 시각을 어디서 얻었나. [TimeReading.origin] 에 담긴다. */
public enum class TimeOrigin {
    /** NTP(UDP) 서버. 왕복 지연을 반으로 나눠 보정하므로 보통 수십 밀리초 안쪽이다. */
    NTP,

    /** HTTP 응답의 `Date` 헤더. 헤더가 초까지만 적어 오차가 0.5초 이상이다. */
    HTTP_DATE,

    /** 소비자가 끼운 [TimeSource]. */
    CUSTOM,

    /** 기기 벽시계. 사용자가 바꿀 수 있어 믿을 수 없다 — 모든 출처가 실패했을 때만 쓴다. */
    DEVICE_CLOCK,
}
