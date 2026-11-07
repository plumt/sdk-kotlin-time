package net.plumt.time

/** [TimeSyncException] 의 원인. */
public enum class FailureReason {
    /** 네트워크에 연결돼 있지 않다 — [NetworkMonitor] 가 알렸거나 모든 시도가 "네트워크 도달 불가" 였다. */
    NO_NETWORK,

    /** 제한 시간 안에 응답이 없었다. 묶음 실패에서는 모든 시도가 시간 초과였다는 뜻이다. */
    TIMEOUT,

    /** 호스트 이름을 찾지 못했다(DNS). */
    HOST_NOT_FOUND,

    /** 연결 거부·포트 도달 불가·그 밖의 입출력 오류. */
    CONNECTION_FAILED,

    /** NTP 서버가 Kiss-o'-Death(stratum 0 + 4글자 코드, 예 `RATE`·`DENY`)로 거절했다. 그 서버는 한동안 쉰다. */
    KISS_OF_DEATH,

    /** NTP stratum 이 0(코드 없음)이거나 16 이상(동기화 안 된 서버)이다. */
    INVALID_STRATUM,

    /** NTP 응답의 윤초 표시(LI)가 3 — 서버 시계가 동기화돼 있지 않다. */
    SERVER_UNSYNCHRONIZED,

    /** 응답 형식이 틀렸다 — 짧은 패킷, 모드·버전 오류, 원본 시각 불일치(다른 요청의 응답·위조), 빈 시각, 거꾸로 된 시각, 과한 루트 거리. */
    INVALID_RESPONSE,

    /** 왕복 지연이 설정한 상한을 넘었다 — 편도 지연 추정 오차가 커서 버린다. */
    ROUND_TRIP_TOO_LONG,

    /** 서버가 준 시각이 받아들일 범위(2025-01-01 ~ 2200-01-01 UTC) 밖이다. */
    IMPLAUSIBLE_TIME,

    /** HTTP 응답에 `Date` 헤더가 없다. */
    HTTP_DATE_MISSING,

    /** HTTP `Date` 헤더를 IMF-fixdate·RFC 850·asctime 어느 형식으로도 읽지 못했다. */
    HTTP_DATE_INVALID,

    /** 끼운 [TimeSource] 가 분류할 수 없는 예외를 던졌다(원래 예외가 `cause`). */
    SOURCE_ERROR,

    /** 모든 출처가 실패했고 원인이 한 가지로 모이지 않는다. 시도별 실패는 [TimeSyncException.failures]. */
    ALL_SOURCES_FAILED,

    /** 네트워크를 기다리지 않는 경로([TrustedClock.asJavaClock])에서 쓸 동기화가 없고 정책이 기기 시계를 막았다. */
    NOT_SYNCED,
}

/**
 * 시각 동기화 실패. 이 모듈이 던지는 고유 예외는 이것 하나다(틀린 인자는 `IllegalArgumentException`).
 *
 * 시도 하나의 실패는 [source] 에 출처 이름을 담는다. 여러 출처를 차례로 시도한 끝의 실패는 [failures] 에
 * 시도별 실패를 순서대로 담고, [reason] 은 그 원인들을 모은 값이다 — 전부 같은 원인(`NO_NETWORK`·`TIMEOUT`)이면
 * 그 원인, 섞여 있으면 [FailureReason.ALL_SOURCES_FAILED]. 원래 예외는 언제나 `cause` 로 남는다.
 *
 * Java 의 `java.time.Clock` 계약에 맞추려고 검사 예외가 아닌 `RuntimeException` 이다.
 *
 * @property reason 원인
 * @property source 실패한 출처 이름(호스트·URL). 묶음 실패는 `null`
 * @property failures 묶음 실패의 시도별 실패. 시도 하나의 실패는 빈 목록
 */
public class TimeSyncException @JvmOverloads constructor(
    public val reason: FailureReason,
    message: String,
    cause: Throwable? = null,
    public val source: String? = null,
    public val failures: List<TimeSyncException> = emptyList(),
) : RuntimeException(message, cause)
