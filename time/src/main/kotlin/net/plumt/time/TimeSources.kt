package net.plumt.time

import net.plumt.time.internal.HttpDateTimeSource
import net.plumt.time.internal.NtpTimeSource
import java.net.URI
import java.net.URISyntaxException

/** 기본 [TimeSource] 를 만든다. */
public object TimeSources {
    /** NTP 표준 포트. */
    public const val NTP_PORT: Int = 123

    private const val MAX_HOST_LENGTH = 253
    private const val MAX_PORT = 65_535

    /**
     * SNTP(RFC 4330·5905) 클라이언트 출처. UDP [port] 로 [host] 에 묻는다.
     *
     * @throws IllegalArgumentException [host] 가 비었거나 공백·`/` 를 담았거나 253자를 넘을 때, [port] 가 1..65535 밖일 때
     */
    @JvmStatic
    @JvmOverloads
    public fun ntp(host: String, port: Int = NTP_PORT): TimeSource {
        requireHost(host)
        require(port in 1..MAX_PORT) { "port 는 1..$MAX_PORT 이어야 한다: $port" }
        return NtpTimeSource(host, port)
    }

    /**
     * HTTP `Date` 헤더 출처. 캐시를 끈 `HEAD` 요청을 보내고 리다이렉트는 따라가지 않는다.
     *
     * 기본은 `https` 만 받는다 — 평문 `http` 는 중간자(공유기·캡티브 포털)가 시각을 바꿀 수 있다.
     * 시험 서버처럼 평문이 꼭 필요하면 [allowCleartext] 를 켠다.
     *
     * @throws IllegalArgumentException URL 형식이 틀렸거나, 호스트가 없거나, 사용자 정보(`user@`)를 담았거나,
     *   스킴이 `https`(평문 허용 시 `http` 도)가 아닐 때
     */
    @JvmStatic
    @JvmOverloads
    public fun httpDate(url: String, allowCleartext: Boolean = false): TimeSource {
        val uri = try {
            URI(url)
        } catch (e: URISyntaxException) {
            throw IllegalArgumentException("URL 형식이 틀렸다: '$url' — ${e.reason}", e)
        }
        val scheme = uri.scheme?.lowercase()
        val allowed = if (allowCleartext) setOf("https", "http") else setOf("https")
        require(scheme in allowed) { "URL 스킴은 ${allowed.joinToString("·")} 이어야 한다: '$url'" }
        require(!uri.host.isNullOrEmpty()) { "URL 에 호스트가 없다: '$url'" }
        require(uri.rawUserInfo == null) { "URL 에 사용자 정보를 넣지 않는다(이름이 로그·예외에 남는다)" }
        return HttpDateTimeSource(uri.toString(), uri.toURL())
    }

    private fun requireHost(host: String) {
        require(host.isNotBlank()) { "host 가 비었다" }
        require(host.length <= MAX_HOST_LENGTH) { "host 는 ${MAX_HOST_LENGTH}자 이하여야 한다: ${host.length}자" }
        require(host.none { it.isWhitespace() || it == '/' }) { "host 에 공백·'/' 를 넣지 않는다(호스트 이름만): '$host'" }
    }
}

/**
 * 기본 서버 목록. NTP 서버는 어느 지역이든 **UTC** 를 준다 — 지역은 응답 지연만 바꾸고 시간대와 무관하다.
 * 시간대는 [TrustedClock.Builder.zone] 으로 따로 고른다.
 */
public object TimeServers {
    /** 한국에서 가까운 NTP 서버(기본값). 한국표준과학연구원 → 구글 → NTP 풀 한국 구역. */
    @JvmField
    public val KOREA_NTP: List<String> = listOf("time.kriss.re.kr", "time.google.com", "kr.pool.ntp.org")

    /** 지역을 가리지 않는 NTP 서버. 애니캐스트(가까운 곳이 응답) → 구글 → NTP 풀 전역. */
    @JvmField
    public val GLOBAL_NTP: List<String> = listOf("time.cloudflare.com", "time.google.com", "pool.ntp.org")

    /** UDP 123 이 막힌 망에서 쓸 HTTPS `Date` 출처(한국 기본값). */
    @JvmField
    public val KOREA_HTTP: List<String> = listOf("https://www.google.com", "https://www.naver.com")

    /** 지역을 가리지 않는 HTTPS `Date` 출처. */
    @JvmField
    public val GLOBAL_HTTP: List<String> = listOf("https://www.google.com", "https://www.cloudflare.com")
}
