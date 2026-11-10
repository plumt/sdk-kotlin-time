package net.plumt.time.internal

import net.plumt.time.FailureReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.PortUnreachableException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class FailuresTest {

    private fun reason(e: Throwable) = Failures.classify(e, "s").reason

    @Test
    fun `예외를 원인별로 나눈다`() {
        assertEquals(FailureReason.TIMEOUT, reason(SocketTimeoutException()))
        assertEquals(FailureReason.HOST_NOT_FOUND, reason(UnknownHostException("x")))
        assertEquals(FailureReason.NO_NETWORK, reason(NoRouteToHostException()))
        assertEquals(FailureReason.NO_NETWORK, reason(SocketException("Network is unreachable (connect failed)")))
        assertEquals(FailureReason.NO_NETWORK, reason(SocketException("sendto failed: ENETUNREACH")))
        assertEquals(FailureReason.CONNECTION_FAILED, reason(SocketException("Connection reset")))
        assertEquals(FailureReason.CONNECTION_FAILED, reason(SocketException()))
        assertEquals(FailureReason.CONNECTION_FAILED, reason(ConnectException()))
        assertEquals(FailureReason.CONNECTION_FAILED, reason(PortUnreachableException()))
        assertEquals(FailureReason.CONNECTION_FAILED, reason(IOException("x")))
        assertEquals(FailureReason.SOURCE_ERROR, reason(SecurityException("권한")))
        assertEquals(FailureReason.SOURCE_ERROR, reason(IllegalStateException()))
    }

    @Test
    fun `원인·출처·메시지를 남긴다 — 메시지 없는 예외는 클래스 이름, 긴 메시지는 자른다`() {
        val io = IOException("끊김")
        val e = Failures.classify(io, "srv")
        assertSame(io, e.cause)
        assertEquals("srv", e.source)
        assertEquals("srv: 끊김", e.message)
        assertEquals("srv: SocketTimeoutException", Failures.classify(SocketTimeoutException(), "srv").message)
        assertEquals(5 + 200, Failures.classify(IOException("x".repeat(1_000)), "srv").message!!.length)
    }

    @Test
    fun `묶음 — 빈 목록은 ALL_SOURCES_FAILED 이고 원인이 없다`() {
        val e = Failures.aggregate(emptyList())
        assertEquals(FailureReason.ALL_SOURCES_FAILED, e.reason)
        assertEquals(null, e.cause)
        assertTrue(e.failures.isEmpty())
    }

    @Test
    fun `묶음 — 원인이 하나면 그 원인`() {
        listOf(FailureReason.TIMEOUT, FailureReason.KISS_OF_DEATH, FailureReason.HTTP_DATE_INVALID).forEach { r ->
            val e = Failures.aggregate(listOf(SyncError(r, "a", null, "a"), SyncError(r, "b", null, "b")))
            assertEquals(r, e.reason)
        }
    }

    @Test
    fun `묶음 — 시간 초과와 연결 없음이 섞이면 ALL_SOURCES_FAILED`() {
        val e = Failures.aggregate(
            listOf(
                SyncError(FailureReason.TIMEOUT, "a", null, "a"),
                SyncError(FailureReason.NO_NETWORK, "b", null, "b"),
            ),
        )
        assertEquals(FailureReason.ALL_SOURCES_FAILED, e.reason)
    }
}
