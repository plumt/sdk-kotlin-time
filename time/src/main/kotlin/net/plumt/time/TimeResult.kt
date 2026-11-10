package net.plumt.time

/**
 * [TrustedClock.now]·[TrustedClock.sync] 의 결과. 닫힌 집합이다 — `when` 에 `else` 없이 두 경우를 다룬다.
 *
 * 예상되는 실패(연결 없음·시간 초과·서버 응답 이상)는 예외로 던지지 않고 [Failure] 로 돌려준다.
 */
public sealed interface TimeResult {

    /**
     * 쓸 수 있는 시각을 얻었다. 새로 맞춘 값일 수도, 정책이 허락한 물러난 값일 수도 있다 —
     * [TimeReading.isStale]·[TimeReading.isDeviceFallback]·[TimeReading.failure] 로 가린다.
     *
     * @property reading 읽은 시각
     */
    public class Success(public val reading: TimeReading) : TimeResult {
        override fun equals(other: Any?): Boolean = other is Success && other.reading == reading

        override fun hashCode(): Int = reading.hashCode()

        override fun toString(): String = "Success($reading)"
    }

    /**
     * 쓸 시각이 없다 — 동기화가 실패했고 [FallbackPolicy] 가 물러남을 막았다([TrustedClock.sync] 는 정책과 무관).
     *
     * @property failure 실패 원인
     */
    public class Failure(public val failure: TimeFailure) : TimeResult {
        override fun equals(other: Any?): Boolean = other is Failure && other.failure == failure

        override fun hashCode(): Int = failure.hashCode()

        override fun toString(): String = "Failure($failure)"
    }
}

/**
 * 시각 동기화 실패 한 건.
 *
 * 시도 하나의 실패는 [source] 에 출처 이름을 담는다. 여러 출처를 차례로 시도한 끝의 실패는 [attempts] 에 시도별 실패를
 * 순서대로 담고, [reason] 은 그 원인들을 모은 값이다 — 전부 같은 원인이면 그 원인, 섞여 있으면
 * [FailureReason.ALL_SOURCES_FAILED].
 *
 * @property reason 원인
 * @property message 사람이 읽을 설명(출처 이름과 응답 일부를 담을 수 있다)
 * @property source 실패한 출처 이름(호스트·URL). 묶음 실패는 `null`
 * @property cause 원래 예외(소켓 오류 등). 없으면 `null`
 * @property attempts 묶음 실패의 시도별 실패. 시도 하나의 실패는 빈 목록
 */
public class TimeFailure internal constructor(
    public val reason: FailureReason,
    public val message: String,
    public val source: String?,
    public val cause: Throwable?,
    public val attempts: List<TimeFailure>,
) {
    override fun equals(other: Any?): Boolean = other is TimeFailure &&
        reason == other.reason &&
        message == other.message &&
        source == other.source &&
        cause == other.cause &&
        attempts == other.attempts

    override fun hashCode(): Int {
        var h = reason.hashCode()
        h = 31 * h + message.hashCode()
        h = 31 * h + (source?.hashCode() ?: 0)
        h = 31 * h + (cause?.hashCode() ?: 0)
        h = 31 * h + attempts.hashCode()
        return h
    }

    override fun toString(): String = "TimeFailure($reason: $message)"
}

/** 콜백 형태 [TrustedClock.now]·[TrustedClock.sync] 의 결과 받기. 메인 스레드에서 한 번 불린다. */
public fun interface TimeCallback {
    /** 결과를 받는다. 메인 스레드에서 불린다. */
    public fun onResult(result: TimeResult)
}

/**
 * 콜백 형태 호출의 취소 핸들. [cancel] 하면 진행 중인 작업을 멈추고 결과 콜백을 부르지 않는다.
 * 이미 결과를 받았거나 이미 취소했으면 아무것도 하지 않는다.
 */
public interface TimeCallHandle {
    /** 결과를 아직 기다리는 중인가. 결과를 받았거나 취소했으면 `false`. */
    public val isActive: Boolean

    /** 작업을 멈추고 결과 콜백을 부르지 않게 한다. 여러 번 불러도 된다. */
    public fun cancel()
}
