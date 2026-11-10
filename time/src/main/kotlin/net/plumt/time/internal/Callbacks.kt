package net.plumt.time.internal

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import net.plumt.time.TimeCallHandle
import net.plumt.time.TimeCallback
import net.plumt.time.TimeResult

/** [block] 을 [this] 범위(메인 디스패처)에서 돌리고 결과를 [callback] 으로 한 번 준다. 취소됐으면 부르지 않는다. */
internal fun CoroutineScope.launchCallback(block: suspend () -> TimeResult, callback: TimeCallback): TimeCallHandle {
    val job = launch {
        val result = block()
        ensureActive()
        callback.onResult(result)
    }
    return JobHandle(job)
}

internal class JobHandle(private val job: Job) : TimeCallHandle {
    override val isActive: Boolean get() = job.isActive

    override fun cancel() {
        job.cancel()
    }

    override fun toString(): String = "TimeCallHandle(active=${job.isActive})"
}

/** 이미 끝난 호출의 핸들(가짜 시계가 바로 결과를 줄 때). */
internal object CompletedHandle : TimeCallHandle {
    override val isActive: Boolean get() = false

    override fun cancel() = Unit

    override fun toString(): String = "TimeCallHandle(active=false)"
}
