package net.plumt.time.internal

import net.plumt.time.FailureReason
import net.plumt.time.TimeSyncException
import java.io.IOException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/** 출처가 던진 예외를 원인별 [TimeSyncException] 으로 바꾼다. 원래 예외는 cause 로 남긴다. */
internal object Failures {

    fun classify(error: Throwable, source: String): TimeSyncException {
        if (error is TimeSyncException) {
            return if (error.source != null) {
                error
            } else {
                TimeSyncException(error.reason, error.message ?: error.reason.name, error, source)
            }
        }
        val reason = when (error) {
            is SocketTimeoutException -> FailureReason.TIMEOUT
            is UnknownHostException -> FailureReason.HOST_NOT_FOUND
            is NoRouteToHostException -> FailureReason.NO_NETWORK
            is SocketException -> if (isNetworkUnreachable(error)) FailureReason.NO_NETWORK else FailureReason.CONNECTION_FAILED
            is IOException -> FailureReason.CONNECTION_FAILED
            else -> FailureReason.SOURCE_ERROR
        }
        val detail = error.message?.take(MAX_DETAIL) ?: error.javaClass.simpleName
        return TimeSyncException(reason, "$source: $detail", error, source)
    }

    /** 여러 출처를 모두 시도한 끝의 실패. 모든 시도의 원인이 같으면 그 원인, 섞이면 ALL_SOURCES_FAILED. */
    fun aggregate(failures: List<TimeSyncException>): TimeSyncException {
        val reasons = failures.map { it.reason }.toSet()
        val reason = reasons.singleOrNull() ?: FailureReason.ALL_SOURCES_FAILED
        val summary = failures.joinToString(", ") { "${it.source}=${it.reason}" }
        return TimeSyncException(reason, "모든 시각 출처가 실패했다: $summary", failures.firstOrNull(), null, failures.toList())
    }

    private fun isNetworkUnreachable(error: SocketException): Boolean {
        val message = error.message?.lowercase() ?: return false
        return "network is unreachable" in message || "enetunreach" in message
    }

    private const val MAX_DETAIL = 200
}
