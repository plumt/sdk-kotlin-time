package net.plumt.time

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * 한 번 읽은 현재 시각과 그 시각을 얼마나 믿을 수 있는지.
 *
 * [epochMillis] 는 어느 Android 버전에서나 쓸 수 있다. `java.time` 변환([toInstant]·[toZonedDateTime]·
 * [toLocalDateTime])은 API 26 이상이거나 소비 앱이 core library desugaring 을 켰을 때만 부른다.
 *
 * @property epochMillis UTC epoch 밀리초
 * @property origin 출처. [TimeOrigin.DEVICE_CLOCK] 이면 기기 시계로 물러난 값이다
 * @property uncertaintyMillis 오차 추정(반경, 밀리초). 동기화 오차에 경과 시간만큼의 시계 편차(100ppm)를 더한다.
 *   기기 시계로 물러났으면 알 수 없어 `null`
 * @property sourceName 출처 이름(호스트·URL). 기기 시계면 `null`
 * @property ageMillis 마지막 동기화 뒤 흐른 단조 시간. 기기 시계면 0
 * @property isStale 갱신 주기를 넘긴 지난 동기화로 계산한 값이면 `true`
 * @property deviceClockOffsetMillis 이 시각 − 기기 벽시계. 양수면 기기 시계가 늦다. 기기 시계로 물러났으면 0
 * @property failure 지난 동기화나 기기 시계로 물러난 이유. 새로 맞춘 값이거나 유효한 오프셋이면 `null`
 * @property zoneId 변환 함수가 인자 없이 쓰는 시간대 ID(기본 `Asia/Seoul`)
 */
public class TimeReading internal constructor(
    public val epochMillis: Long,
    public val origin: TimeOrigin,
    public val uncertaintyMillis: Long?,
    public val sourceName: String?,
    public val ageMillis: Long,
    public val isStale: Boolean,
    public val deviceClockOffsetMillis: Long,
    public val failure: TimeSyncException?,
    public val zoneId: String,
) {
    /** 기기 시계로 물러난 값이면 `true` — 사용자가 바꾼 시계일 수 있다. */
    public val isDeviceFallback: Boolean get() = origin == TimeOrigin.DEVICE_CLOCK

    /** UTC 순간. */
    public fun toInstant(): Instant = Instant.ofEpochMilli(epochMillis)

    /** [zoneId] 시간대의 날짜·시각. */
    public fun toZonedDateTime(): ZonedDateTime = toZonedDateTime(ZoneId.of(zoneId))

    /** [zone] 시간대의 날짜·시각. */
    public fun toZonedDateTime(zone: ZoneId): ZonedDateTime = toInstant().atZone(zone)

    /** [zoneId] 시간대의 벽시계 날짜·시각(시간대 정보 없음). */
    public fun toLocalDateTime(): LocalDateTime = toZonedDateTime().toLocalDateTime()

    /** [zone] 시간대의 벽시계 날짜·시각(시간대 정보 없음). */
    public fun toLocalDateTime(zone: ZoneId): LocalDateTime = toZonedDateTime(zone).toLocalDateTime()

    override fun toString(): String = buildString {
        append("TimeReading(").append(epochMillis).append(", ").append(origin)
        sourceName?.let { append(' ').append(it) }
        append(", ±").append(uncertaintyMillis?.toString() ?: "?").append(" ms")
        append(", age=").append(ageMillis).append(" ms")
        if (isStale) append(", stale")
        failure?.let { append(", failure=").append(it.reason) }
        append(", zone=").append(zoneId).append(')')
    }
}
