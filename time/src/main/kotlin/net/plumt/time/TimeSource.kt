package net.plumt.time

/**
 * 서버 시각 한 번 조회. 기본 구현은 [TimeSources.ntp]·[TimeSources.httpDate] 이고, 사내 시각 서버 같은 출처를
 * 직접 구현해 [TimeConfig.Builder.sources] 로 끼울 수 있다.
 *
 * [query] 는 **블로킹**이다. 라이브러리가 [TimeConfig.dispatcher] 의 스레드에서 부른다.
 * 예상되는 실패는 [TimeSourceResult.Failure] 로 원인을 담아 돌려준다. 던진 예외(`IOException` 등)는 라이브러리가
 * 원인별로 나눈다. 결과는 라이브러리가 왕복 상한·받아들일 시각 범위로 다시 검사한다.
 */
public interface TimeSource {
    /** 실패·결과에 적히는 이름(호스트·URL). 비밀값을 넣지 않는다. */
    public val name: String

    /** 결과의 [TimeReading.origin]. [TimeOrigin.DEVICE_CLOCK] 은 받지 않는다. */
    public val origin: TimeOrigin

    /** 서버 시각을 조회한다. 블로킹. */
    public fun query(request: TimeQuery): TimeSourceResult
}

/** [TimeSource.query] 의 결과. 닫힌 집합이다. */
public sealed interface TimeSourceResult {
    /**
     * 조회 성공.
     *
     * @property sample 조회한 시각
     */
    public class Success(public val sample: TimeSample) : TimeSourceResult {
        override fun equals(other: Any?): Boolean = other is Success && other.sample == sample

        override fun hashCode(): Int = sample.hashCode()

        override fun toString(): String = "Success($sample)"
    }

    /**
     * 조회 실패. 이 출처를 건너뛰고 다음 출처를 시도한다.
     *
     * @property reason 원인
     * @property message 사람이 읽을 설명. 출처 이름은 라이브러리가 앞에 붙인다
     * @property cause 원래 예외. 없으면 `null`
     */
    public class Failure(
        public val reason: FailureReason,
        public val message: String,
        public val cause: Throwable?,
    ) : TimeSourceResult {
        /** 원래 예외 없이 실패를 만든다. */
        public constructor(reason: FailureReason, message: String) : this(reason, message, null)

        override fun equals(other: Any?): Boolean =
            other is Failure && other.reason == reason && other.message == message && other.cause == cause

        override fun hashCode(): Int = (reason.hashCode() * 31 + message.hashCode()) * 31 + (cause?.hashCode() ?: 0)

        override fun toString(): String = "Failure($reason: $message)"
    }
}

/**
 * [TimeSource.query] 가 받는 조회 조건.
 *
 * @property timeoutMillis 응답을 기다릴 상한(밀리초)
 * @property monotonicClock 기준점을 잴 단조 시계 — [TimeSample.anchorElapsedMillis] 는 이 시계로 잰다
 */
public class TimeQuery internal constructor(
    public val timeoutMillis: Int,
    public val monotonicClock: MonotonicClock,
) {
    override fun toString(): String = "TimeQuery(timeout=$timeoutMillis)"
}

/**
 * 조회 결과 하나.
 *
 * @property serverEpochMillis [anchorElapsedMillis] 순간의 서버 시각(UTC epoch 밀리초)
 * @property anchorElapsedMillis 그 순간의 단조 시계 값
 * @property roundTripMillis 측정한 왕복 지연(0 이상)
 * @property uncertaintyMillis 그 순간 서버 시각의 오차 추정(0 이상) — 참값이 이 폭 안에 있다고 보는 반경
 * @throws IllegalArgumentException 왕복·오차가 음수일 때
 */
public class TimeSample(
    public val serverEpochMillis: Long,
    public val anchorElapsedMillis: Long,
    public val roundTripMillis: Long,
    public val uncertaintyMillis: Long,
) {
    init {
        require(roundTripMillis >= 0) { "roundTripMillis 는 0 이상이어야 한다: $roundTripMillis" }
        require(uncertaintyMillis >= 0) { "uncertaintyMillis 는 0 이상이어야 한다: $uncertaintyMillis" }
    }

    override fun equals(other: Any?): Boolean = other is TimeSample &&
        serverEpochMillis == other.serverEpochMillis &&
        anchorElapsedMillis == other.anchorElapsedMillis &&
        roundTripMillis == other.roundTripMillis &&
        uncertaintyMillis == other.uncertaintyMillis

    override fun hashCode(): Int {
        var h = serverEpochMillis.hashCode()
        h = 31 * h + anchorElapsedMillis.hashCode()
        h = 31 * h + roundTripMillis.hashCode()
        h = 31 * h + uncertaintyMillis.hashCode()
        return h
    }

    override fun toString(): String =
        "TimeSample(server=$serverEpochMillis, anchor=$anchorElapsedMillis, rtt=$roundTripMillis, ±$uncertaintyMillis ms)"
}
