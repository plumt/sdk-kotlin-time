package net.plumt.time

import java.util.Collections

/**
 * [TimeFailure] 의 원인. 값이 늘 수 있어 열거형이 아니다 — 앱의 `when` 에는 `else` 를 둔다.
 * 같은 원인은 언제나 같은 인스턴스라 `==`·`===` 로 비교한다.
 */
public class FailureReason internal constructor(
    /** 상수 이름. 로그·분석에 쓴다. */
    public val name: String,
) {
    override fun toString(): String = name

    /** 정의된 값과 목록. */
    public companion object {
        private val all = ArrayList<FailureReason>()
        private fun of(name: String) = FailureReason(name).also { all += it }

        /** 네트워크에 연결돼 있지 않다 — [NetworkMonitor] 가 알렸거나 모든 시도가 "네트워크 도달 불가" 였다. */
        @JvmField public val NO_NETWORK: FailureReason = of("NO_NETWORK")

        /** 제한 시간 안에 응답이 없었다. 묶음 실패에서는 모든 시도가 시간 초과였다는 뜻이다. */
        @JvmField public val TIMEOUT: FailureReason = of("TIMEOUT")

        /** 호스트 이름을 찾지 못했다(DNS). */
        @JvmField public val HOST_NOT_FOUND: FailureReason = of("HOST_NOT_FOUND")

        /** 연결 거부·포트 도달 불가·그 밖의 입출력 오류. */
        @JvmField public val CONNECTION_FAILED: FailureReason = of("CONNECTION_FAILED")

        /** NTP 서버가 Kiss-o'-Death(stratum 0 + 4글자 코드, 예 `RATE`·`DENY`)로 거절했다. 그 서버는 한동안 쉰다. */
        @JvmField public val KISS_OF_DEATH: FailureReason = of("KISS_OF_DEATH")

        /** NTP stratum 이 0(코드 없음)이거나 16 이상(동기화 안 된 서버)이다. */
        @JvmField public val INVALID_STRATUM: FailureReason = of("INVALID_STRATUM")

        /** NTP 응답의 윤초 표시(LI)가 3 — 서버 시계가 동기화돼 있지 않다. */
        @JvmField public val SERVER_UNSYNCHRONIZED: FailureReason = of("SERVER_UNSYNCHRONIZED")

        /** 응답 형식이 틀렸다 — 짧은 패킷, 모드·버전 오류, 원본 시각 불일치(다른 요청의 응답·위조), 빈 시각, 거꾸로 된 시각, 과한 루트 거리. */
        @JvmField public val INVALID_RESPONSE: FailureReason = of("INVALID_RESPONSE")

        /** 왕복 지연이 설정한 상한을 넘었다 — 편도 지연 추정 오차가 커서 버린다. */
        @JvmField public val ROUND_TRIP_TOO_LONG: FailureReason = of("ROUND_TRIP_TOO_LONG")

        /** 서버가 준 시각이 받아들일 범위(2025-01-01 ~ 2200-01-01 UTC) 밖이다. */
        @JvmField public val IMPLAUSIBLE_TIME: FailureReason = of("IMPLAUSIBLE_TIME")

        /** HTTP 응답에 `Date` 헤더가 없다. */
        @JvmField public val HTTP_DATE_MISSING: FailureReason = of("HTTP_DATE_MISSING")

        /** HTTP `Date` 헤더를 IMF-fixdate·RFC 850·asctime 어느 형식으로도 읽지 못했다. */
        @JvmField public val HTTP_DATE_INVALID: FailureReason = of("HTTP_DATE_INVALID")

        /** 끼운 [TimeSource] 가 분류할 수 없는 예외를 던졌다(원래 예외가 [TimeFailure.cause]). */
        @JvmField public val SOURCE_ERROR: FailureReason = of("SOURCE_ERROR")

        /** 모든 출처가 실패했고 원인이 한 가지로 모이지 않는다. 시도별 실패는 [TimeFailure.attempts]. */
        @JvmField public val ALL_SOURCES_FAILED: FailureReason = of("ALL_SOURCES_FAILED")

        /** 정의된 모든 값(정의 순서). */
        public val values: List<FailureReason> get() = Collections.unmodifiableList(ArrayList(all))
    }
}

/**
 * 시각을 어디서 얻었나. [TimeReading.origin] 에 담긴다. 출처가 늘 수 있어 열거형이 아니다 — 앱의 `when` 에는 `else` 를 둔다.
 */
public class TimeOrigin internal constructor(
    /** 상수 이름. 로그·분석에 쓴다. */
    public val name: String,
    /** 저장 형식(버전 2)에 쓰는 고정 키. 이름을 바꿔도 저장된 값은 그대로 읽힌다. */
    internal val storageKey: String,
) {
    override fun toString(): String = name

    /** 정의된 값과 목록. */
    public companion object {
        private val all = ArrayList<TimeOrigin>()
        private fun of(name: String, key: String) = TimeOrigin(name, key).also { all += it }

        /** NTP(UDP) 서버. 왕복 지연을 반으로 나눠 보정하므로 보통 수십 밀리초 안쪽이다. */
        @JvmField public val NTP: TimeOrigin = of("NTP", "ntp")

        /** HTTP 응답의 `Date` 헤더. 헤더가 초까지만 적어 오차가 0.5초 이상이다. */
        @JvmField public val HTTP_DATE: TimeOrigin = of("HTTP_DATE", "http-date")

        /** 앱이 끼운 [TimeSource]. */
        @JvmField public val CUSTOM: TimeOrigin = of("CUSTOM", "custom")

        /** 기기 벽시계. 사용자가 바꿀 수 있어 믿을 수 없다 — 모든 출처가 실패했을 때만 쓴다. */
        @JvmField public val DEVICE_CLOCK: TimeOrigin = of("DEVICE_CLOCK", "device")

        /** 정의된 모든 값(정의 순서). */
        public val values: List<TimeOrigin> get() = Collections.unmodifiableList(ArrayList(all))

        /** 버전 2 저장 키로 찾는다. */
        internal fun fromStorageKey(key: String?): TimeOrigin? = all.firstOrNull { it.storageKey == key }

        /** 버전 1 저장 형식(열거형 이름)으로 찾는다. */
        internal fun fromLegacyName(name: String?): TimeOrigin? = all.firstOrNull { it.name == name }
    }
}

/**
 * 지금 유효한 동기화가 없고 새 동기화도 실패했을 때 [TrustedClock.now] 가 무엇을 돌려줄지. 닫힌 집합이다.
 *
 * "유효" 는 마지막 동기화 뒤 경과가 갱신 주기 안쪽이라는 뜻이다. 갱신 주기는 지났지만 최대 보관 기간 안쪽인
 * 오프셋은 **지난 동기화**(stale)라 부른다 — 같은 부팅의 단조 시계로 이어 세므로 기기 시계보다 훨씬 믿을 만하다.
 */
public sealed interface FallbackPolicy {
    /** 지난 동기화 → 기기 시계 순서로 물러난다(기본). 결과의 [TimeReading.isDeviceFallback] 로 알 수 있다. */
    public data object DeviceClock : FallbackPolicy

    /** 지난 동기화까지만 쓰고 기기 시계는 쓰지 않는다. 둘 다 없으면 [TimeResult.Failure]. */
    public data object LastSyncOnly : FallbackPolicy

    /** 물러나지 않는다. 유효한 동기화가 없고 새 동기화도 실패하면 [TimeResult.Failure]. */
    public data object None : FallbackPolicy
}
