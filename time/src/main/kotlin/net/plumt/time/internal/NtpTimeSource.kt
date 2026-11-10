package net.plumt.time.internal

import net.plumt.time.TimeOrigin
import net.plumt.time.TimeQuery
import net.plumt.time.TimeSample
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.security.SecureRandom

/** UDP SNTP 출처. 소켓을 [host]:[port] 에 connect 해 다른 주소에서 온 패킷은 받지 않는다. */
internal class NtpTimeSource(
    private val host: String,
    private val port: Int,
    private val resolver: (String) -> InetAddress = InetAddress::getByName,
    private val nonces: () -> Long = { RANDOM.nextLong().takeIf { it != 0L } ?: 1L },
) : SampleSource() {

    override val name: String = if (port == DEFAULT_PORT) host else "$host:$port"
    override val origin: TimeOrigin = TimeOrigin.NTP

    override fun sample(request: TimeQuery): TimeSample {
        val address = resolver(host)
        val nonce = nonces()
        DatagramSocket().use { socket ->
            socket.soTimeout = request.timeoutMillis
            socket.connect(address, port)
            val out = Ntp.request(nonce)
            val buffer = ByteArray(RECEIVE_BUFFER)
            val packet = DatagramPacket(buffer, buffer.size)
            val clock = request.monotonicClock
            val sentAt = clock.elapsedMillis()
            socket.send(DatagramPacket(out, out.size))
            socket.receive(packet)
            val receivedAt = clock.elapsedMillis()
            return Ntp.parse(name, buffer, packet.length, nonce, receivedAt - sentAt, receivedAt)
        }
    }

    private companion object {
        const val DEFAULT_PORT = 123

        /** 확장 필드·MAC 이 붙은 응답도 잘리지 않게 넉넉히 받는다(앞 48바이트만 읽는다). */
        const val RECEIVE_BUFFER = 512
        val RANDOM = SecureRandom()
    }
}
