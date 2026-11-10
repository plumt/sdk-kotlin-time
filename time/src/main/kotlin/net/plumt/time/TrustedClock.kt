package net.plumt.time

import android.content.Context
import net.plumt.time.internal.DefaultTrustedClock

/**
 * 기기 시계 조작에 흔들리지 않는 현재 시각.
 *
 * NTP → HTTP `Date` → (정책이 허락하면) 기기 시계 순서로 시각을 얻는다. 한 번 맞추면 서버 시각과 그 순간의
 * **단조 시계** 값을 한 쌍으로 보관하고, 이후에는 네트워크 없이 단조 시계 경과만 더한다 — 그래서 사용자가
 * 기기 시계를 바꿔도 [now] 가 그대로다. 갱신 주기가 지나면 [now] 가 다시 맞춘다.
 *
 * 같은 기능을 두 형태로 준다: `suspend` 함수([now]·[sync])와 콜백([now] (callback)·[sync] (callback), 메인 스레드에서
 * 결과를 받고 취소 핸들을 돌려준다). 둘은 같은 내부 로직을 쓴다. 예상되는 실패는 [TimeResult.Failure] 로 돌려주고
 * 던지지 않는다.
 *
 * 스레드 안전하다. 동시에 여러 곳에서 불러도 네트워크 조회는 한 번만 나가고 결과를 나눠 받는다.
 * 인스턴스는 앱 전체에서 하나만 만들어 들고 다닌다(DI 로 넘긴다). 단위 테스트는 [FakeTrustedClock] 을 쓴다.
 *
 * ```kotlin
 * val clock = TrustedClock.create(context)          // 한국 서버·Asia/Seoul
 * when (val r = clock.now()) {
 *     is TimeResult.Success -> r.reading.toLocalDateTime()
 *     is TimeResult.Failure -> showRetry(r.failure.reason)
 * }
 * ```
 */
public interface TrustedClock {
    /** 변환 함수가 인자 없이 쓰는 시간대 ID. */
    public val zoneId: String

    /**
     * 현재 시각. 유효한 오프셋이 있으면 네트워크 없이 곧바로 돌려준다. 없거나 갱신 주기가 지났으면 동기화하고,
     * 실패하면 [FallbackPolicy] 에 따라 물러난다(물러난 이유는 [TimeReading.failure]). 정책이 물러남을 막았는데 쓸
     * 시각이 없으면 [TimeResult.Failure].
     *
     * 직전 자동 동기화가 실패했으면 대기(30초부터 두 배씩, 상한 30분)가 끝나기 전에는 다시 조회하지 않고 바로 물러난다.
     * 메인 스레드에서 불러도 된다 — 블로킹 조회는 [TimeConfig.dispatcher] 에서 돈다.
     */
    public suspend fun now(): TimeResult

    /**
     * 캐시와 대기를 보지 않고 지금 동기화한다. 앱 시작·네트워크 복구 시점에 부른다.
     * 모든 출처가 실패하면 정책과 무관하게 [TimeResult.Failure].
     */
    public suspend fun sync(): TimeResult

    /** [now] 의 콜백 형태. 결과는 메인 스레드에서 [callback] 으로 한 번 온다. 취소하면 부르지 않는다. */
    public fun now(callback: TimeCallback): TimeCallHandle

    /** [sync] 의 콜백 형태. 결과는 메인 스레드에서 [callback] 으로 한 번 온다. 취소하면 부르지 않는다. */
    public fun sync(callback: TimeCallback): TimeCallHandle

    /**
     * 네트워크를 기다리지 않고 보관한 오프셋으로만 계산한다. 지난 동기화(stale)도 [TimeReading.isStale] 로 표시해
     * 돌려준다. 오프셋이 없거나 최대 보관 기간을 넘었거나 재부팅으로 무효면 `null`.
     */
    public fun peek(): TimeReading?

    /** 보관한 오프셋·실패 대기·KoD 휴식을 모두 지운다. 다음 [now] 는 새로 동기화한다. */
    public fun invalidate()

    /**
     * `java.time.Clock` 으로 감싼다. 네트워크를 기다리지 않는다 — [peek] 의 값을 쓰고, 없으면 정책에 따라
     * 기기 시계를 쓴다. 정책이 기기 시계를 막았고 쓸 동기화가 없으면 `millis()` 가 `java.time.DateTimeException` 을
     * 던진다(`Clock` 계약에 결과 타입이 없다 — 먼저 [sync] 한다). 시간대는 [zoneId]. API 26 미만은 desugaring 이 필요하다.
     */
    public fun asJavaClock(): java.time.Clock

    /** 시계를 만든다. */
    public companion object {
        /**
         * 기본 설정(한국 서버, `Asia/Seoul`, 기기 시계 폴백 허용)의 시계를 만든다. Android 단조 시계(`elapsedRealtime`)·
         * 연결 판단(`ConnectivityManager`)·SharedPreferences 저장소를 끼운다.
         */
        @JvmStatic
        public fun create(context: Context): TrustedClock = create(context, TimeConfig.builder().build())

        /** [config] 로 시계를 만든다. [config] 가 정하지 않은 단조 시계·벽시계·연결 판단·저장소는 Android 구현을 끼운다. */
        @JvmStatic
        public fun create(context: Context, config: TimeConfig): TrustedClock = DefaultTrustedClock.create(context, config)
    }
}
