package net.plumt.time

import net.plumt.time.internal.Ntp
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.SocketException
import java.time.Instant
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

/** 2026-11-03T12:00:00Z — 시험의 기준 서버 시각. */
val SERVER_TIME: Long = Instant.parse("2026-11-03T12:00:00Z").toEpochMilli()

/** 손으로 움직이는 단조 시계. */
class FakeMonotonic(var now: Long = 1_000_000L, override var bootId: String? = "boot-1") : MonotonicClock {
    override fun elapsedMillis(): Long = now
    fun advance(millis: Long) {
        now += millis
    }
}

/** 손으로 바꾸는 기기 벽시계 — 사용자의 시계 조작을 흉내 낸다. */
class FakeWall(var now: Long = SERVER_TIME) : WallClock {
    override fun currentTimeMillis(): Long = now
}

/** 응답을 차례로 꺼내는 출처. 함수가 예외를 던지면 그 예외가 조회 실패다. */
class ScriptedSource(
    override val name: String = "fake",
    override val origin: TimeOrigin = TimeOrigin.CUSTOM,
    private val answer: (TimeQuery) -> TimeSample,
) : TimeSource {
    val calls = AtomicInteger()
    override fun query(request: TimeQuery): TimeSample {
        calls.incrementAndGet()
        return answer(request)
    }
}

/** 지금 단조 시계 기준으로 [serverTime] 을 돌려주는 정상 출처. */
fun okSource(
    clock: MonotonicClock,
    name: String = "fake",
    serverTime: () -> Long = { SERVER_TIME },
    roundTrip: Long = 20,
    uncertainty: Long = 15,
    origin: TimeOrigin = TimeOrigin.CUSTOM,
): ScriptedSource = ScriptedSource(name, origin) {
    TimeSample(serverTime(), clock.elapsedMillis(), roundTrip, uncertainty)
}

fun failingSource(name: String, error: () -> Throwable): ScriptedSource = ScriptedSource(name) { throw error() }

fun builder(mono: FakeMonotonic, wall: FakeWall = FakeWall(), vararg sources: TimeSource): TrustedClock.Builder =
    TrustedClock.builder()
        .monotonicClock(mono)
        .wallClock(wall)
        .sources(sources.toList())
        .dispatcher(kotlinx.coroutines.Dispatchers.Unconfined)

/** NTP 타임스탬프 8바이트를 쓴다. */
fun writeNtpTimestamp(buffer: ByteArray, offset: Int, epochMillis: Long) {
    val seconds = (Math.floorDiv(epochMillis, 1000L) + Ntp.NTP_EPOCH_OFFSET_SECONDS) and 0xFFFF_FFFFL
    val fraction = (Math.floorMod(epochMillis, 1000L) shl 32) / 1000L
    writeUInt32(buffer, offset, seconds)
    writeUInt32(buffer, offset + 4, fraction)
}

fun writeUInt32(buffer: ByteArray, offset: Int, value: Long) {
    for (i in 0 until 4) buffer[offset + i] = (value ushr (24 - 8 * i)).toByte()
}

/** NTP 서버 응답을 만든다. 기본은 정상 응답(stratum 2, 버전 4, 모드 4). */
class NtpReply(
    var leap: Int = 0,
    var version: Int = 4,
    var mode: Int = 4,
    var stratum: Int = 2,
    var referenceId: ByteArray = byteArrayOf(0x47, 0x50, 0x53, 0x00),
    var rootDelayRaw: Long = 0,
    var rootDispersionRaw: Long = 0,
    var receive: Long? = SERVER_TIME,
    var transmit: Long? = SERVER_TIME,
    var originateOverride: ByteArray? = null,
    var length: Int = 48,
) {
    fun build(request: ByteArray): ByteArray {
        val out = ByteArray(maxOf(length, 48))
        out[0] = ((leap shl 6) or (version shl 3) or mode).toByte()
        out[1] = stratum.toByte()
        writeUInt32(out, 4, rootDelayRaw)
        writeUInt32(out, 8, rootDispersionRaw)
        referenceId.copyInto(out, 12)
        (originateOverride ?: request.copyOfRange(40, 48)).copyInto(out, 24)
        receive?.let { writeNtpTimestamp(out, 32, it) }
        transmit?.let { writeNtpTimestamp(out, 40, it) }
        return out.copyOf(length)
    }
}

/** 로컬 UDP 가짜 NTP 서버. [reply] 가 `null` 을 주면 응답하지 않는다(시간 초과 시험). */
class FakeNtpServer(var reply: (ByteArray) -> ByteArray?) : AutoCloseable {
    private val socket = DatagramSocket(InetSocketAddress(InetAddress.getLoopbackAddress(), 0))
    val port: Int get() = socket.localPort
    val requests: MutableList<ByteArray> = Collections.synchronizedList(ArrayList())
    private val thread = Thread {
        val buffer = ByteArray(512)
        while (!socket.isClosed) {
            val packet = DatagramPacket(buffer, buffer.size)
            try {
                socket.receive(packet)
            } catch (_: SocketException) {
                break
            }
            val request = buffer.copyOf(packet.length)
            requests += request
            val response = reply(request) ?: continue
            socket.send(DatagramPacket(response, response.size, packet.socketAddress))
        }
    }.apply {
        isDaemon = true
        start()
    }

    override fun close() {
        socket.close()
        thread.join(2_000)
    }
}

/** 로컬 TCP 가짜 HTTP 서버. 요청 머리를 읽고 [respond] 가 준 바이트를 그대로 쓴다. */
class FakeHttpServer(var respond: (List<String>) -> String) : AutoCloseable {
    private val server = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
    val port: Int get() = server.localPort
    val url: String get() = "http://127.0.0.1:$port/"
    val requests: MutableList<List<String>> = Collections.synchronizedList(ArrayList())
    private val thread = Thread {
        while (!server.isClosed) {
            val socket = try {
                server.accept()
            } catch (_: SocketException) {
                break
            }
            Thread {
                socket.use { s ->
                    try {
                        val reader = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.ISO_8859_1))
                        val lines = generateSequence { reader.readLine() }.takeWhile { it.isNotEmpty() }.toList()
                        requests += lines
                        val body = respond(lines)
                        s.getOutputStream().write(body.toByteArray(Charsets.ISO_8859_1))
                        s.getOutputStream().flush()
                    } catch (_: java.io.IOException) {
                        // 클라이언트가 먼저 끊었다(시간 초과 시험)
                    }
                }
            }.apply {
                isDaemon = true
                start()
            }
        }
    }.apply {
        isDaemon = true
        start()
    }

    override fun close() {
        server.close()
        thread.join(2_000)
    }

    companion object {
        fun response(vararg headers: String): String =
            "HTTP/1.1 200 OK\r\n" + headers.joinToString("") { "$it\r\n" } + "Content-Length: 0\r\nConnection: close\r\n\r\n"
    }
}

inline fun <reified T : Throwable> assertThrows(block: () -> Unit): T {
    try {
        block()
    } catch (e: Throwable) {
        if (e is T) return e
        throw AssertionError("${T::class.java.simpleName} 를 기대했으나 ${e.javaClass.simpleName}: ${e.message}", e)
    }
    throw AssertionError("${T::class.java.simpleName} 를 기대했으나 예외가 없었다")
}

suspend inline fun <reified T : Throwable> assertThrowsSuspend(crossinline block: suspend () -> Unit): T {
    try {
        block()
    } catch (e: Throwable) {
        if (e is T) return e
        throw AssertionError("${T::class.java.simpleName} 를 기대했으나 ${e.javaClass.simpleName}: ${e.message}", e)
    }
    throw AssertionError("${T::class.java.simpleName} 를 기대했으나 예외가 없었다")
}
