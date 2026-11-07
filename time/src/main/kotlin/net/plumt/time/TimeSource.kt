package net.plumt.time

/**
 * 서버 시각 한 번 조회. 기본 구현은 [TimeSources.ntp]·[TimeSources.httpDate] 이고, 사내 시각 서버 같은 출처를
 * 직접 구현해 [TrustedClock.Builder.sources] 로 끼울 수 있다.
 *
 * [query] 는 **블로킹**이다. 라이브러리가 [TrustedClock.Builder.dispatcher] 의 스레드에서 부른다.
 * 실패는 [TimeSyncException] 에 원인을 담아 던진다. 다른 예외(`IOException` 등)는 라이브러리가 원인별로 나눈다.
 * 결과는 라이브러리가 왕복 상한·받아들일 시각 범위로 다시 검사한다.
 */
public interface TimeSource {
    /** 실패·결과에 적히는 이름(호스트·URL). 비밀값을 넣지 않는다. */
    public val name: String

    /** 결과의 [TimeReading.origin]. [TimeOrigin.DEVICE_CLOCK] 은 받지 않는다. */
    public val origin: TimeOrigin

    /** 서버 시각을 조회한다. 블로킹. */
    public fun query(request: TimeQuery): TimeSample
}

/**
 * [TimeSource.query] 가 받는 조회 조건.
 *
 * @property timeoutMillis 응답을 기다릴 상한(밀리초)
 * @property monotonicClock 기준점을 잴 단조 시계 — [TimeSample.anchorElapsedMillis] 는 이 시계로 잰다
 */
public class TimeQuery(
    public val timeoutMillis: Int,
    public val monotonicClock: MonotonicClock,
)

/**
 * 조회 결과 하나.
 *
 * @property serverEpochMillis [anchorElapsedMillis] 순간의 서버 시각(UTC epoch 밀리초)
 * @property anchorElapsedMillis 그 순간의 단조 시계 값
 * @property roundTripMillis 측정한 왕복 지연(0 이상)
 * @property uncertaintyMillis 그 순간 서버 시각의 오차 추정(0 이상) — 참값이 이 폭 안에 있다고 보는 반경
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
}
