package net.plumt.time

import android.annotation.SuppressLint
import android.os.Build
import androidx.annotation.RequiresApi
import net.plumt.time.internal.CompletedHandle
import java.time.Clock
import java.time.DateTimeException
import java.time.Instant
import java.time.ZoneId

/**
 * 시험용 [TrustedClock]. 네트워크·기기 없이 앱 단위 테스트에서 시각과 실패를 손으로 정한다.
 *
 * - 시각은 [epochMillis] 로 정하고 [advanceBy] 로 흘린다. 결과의 출처는 [TimeOrigin.NTP], 이름은 `fake`, 오차는 0 이다.
 * - [failWith] 를 부르면 [now]·[sync] 가 그 원인으로 [TimeResult.Failure] 를 돌려주고 [peek] 은 `null` 이다. [succeed] 로 되돌린다.
 * - 콜백 형태는 부른 스레드에서 **바로** 결과를 주고 이미 끝난 핸들을 돌려준다(시험에 메인 루퍼가 필요 없다).
 * - 부른 횟수는 [nowCalls]·[syncCalls]·[invalidateCalls] 로 확인한다.
 *
 * ```kotlin
 * val clock = FakeTrustedClock(1_793_707_200_000L)
 * val viewModel = AttendanceViewModel(clock)
 * clock.advanceBy(86_400_000L)
 * ```
 */
public class FakeTrustedClock(
    initialEpochMillis: Long,
    /** 변환 함수가 인자 없이 쓰는 시간대 ID. */
    override val zoneId: String,
) : TrustedClock {

    /** 기본 시간대 `Asia/Seoul` 의 가짜 시계. */
    public constructor(epochMillis: Long) : this(epochMillis, DEFAULT_ZONE)

    private val lock = Any()
    private var current: Long = initialEpochMillis
    private var failure: TimeFailure? = null
    private var nows = 0
    private var syncs = 0
    private var invalidates = 0

    /** 지금 가짜 시각(UTC epoch 밀리초). 바꾸면 다음 결과부터 쓴다. */
    public var epochMillis: Long
        get() = synchronized(lock) { current }
        set(value) = synchronized(lock) { current = value }

    /** [now] 를 부른 횟수(콜백 형태 포함). */
    public val nowCalls: Int get() = synchronized(lock) { nows }

    /** [sync] 를 부른 횟수(콜백 형태 포함). */
    public val syncCalls: Int get() = synchronized(lock) { syncs }

    /** [invalidate] 를 부른 횟수. */
    public val invalidateCalls: Int get() = synchronized(lock) { invalidates }

    /** 시각을 [millis] 만큼 앞으로(음수면 뒤로) 흘린다. */
    public fun advanceBy(millis: Long) {
        synchronized(lock) { current += millis }
    }

    /** 이후 [now]·[sync] 가 [reason] 으로 실패하게 한다. */
    public fun failWith(reason: FailureReason) {
        synchronized(lock) { failure = TimeFailure(reason, "가짜 실패: $reason", null, null, emptyList()) }
    }

    /** [failWith] 를 되돌린다. */
    public fun succeed() {
        synchronized(lock) { failure = null }
    }

    override suspend fun now(): TimeResult = result { nows += 1 }

    override suspend fun sync(): TimeResult = result { syncs += 1 }

    override fun now(callback: TimeCallback): TimeCallHandle {
        callback.onResult(result { nows += 1 })
        return CompletedHandle
    }

    override fun sync(callback: TimeCallback): TimeCallHandle {
        callback.onResult(result { syncs += 1 })
        return CompletedHandle
    }

    override fun peek(): TimeReading? = synchronized(lock) { if (failure == null) reading(current) else null }

    override fun invalidate() {
        synchronized(lock) { invalidates += 1 }
    }

    @SuppressLint("NewApi") // java.time: API 26 또는 앱의 desugaring — README "API 레벨별 동작"
    override fun asJavaClock(): Clock = FakeJavaClock(this, ZoneId.of(zoneId))

    override fun toString(): String = "FakeTrustedClock(${epochMillis}, zone=$zoneId, failure=${synchronized(lock) { failure?.reason }})"

    private inline fun result(count: () -> Unit): TimeResult = synchronized(lock) {
        count()
        val failed = failure
        if (failed != null) TimeResult.Failure(failed) else TimeResult.Success(reading(current))
    }

    private fun reading(epoch: Long) = TimeReading(
        epochMillis = epoch,
        origin = TimeOrigin.NTP,
        uncertaintyMillis = 0,
        sourceName = FAKE_NAME,
        ageMillis = 0,
        isStale = false,
        deviceClockOffsetMillis = 0,
        failure = null,
        zoneId = zoneId,
    )

    @SuppressLint("NewApi") // java.time: API 26 또는 앱의 desugaring — README "API 레벨별 동작"
    internal fun javaMillis(): Long = peek()?.epochMillis ?: throw DateTimeException("가짜 시계가 실패 상태다(failWith)")

}

private const val DEFAULT_ZONE = "Asia/Seoul"
private const val FAKE_NAME = "fake"

@RequiresApi(Build.VERSION_CODES.O)
private class FakeJavaClock(private val fake: FakeTrustedClock, private val zone: ZoneId) : Clock() {
    override fun getZone(): ZoneId = zone

    override fun withZone(zone: ZoneId): Clock = FakeJavaClock(fake, zone)

    override fun millis(): Long = fake.javaMillis()

    override fun instant(): Instant = Instant.ofEpochMilli(millis())
}
