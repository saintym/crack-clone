package com.crack.scenario.imports

import com.crack.global.exception.BadRequestException
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI
import java.net.URISyntaxException
import java.net.UnknownHostException

/**
 * 바깥 URL을 내려받기 전 검사 (DESIGN.md §11.1).
 *
 * 서버가 사용자 입력 URL로 요청을 보내므로(SSRF) 내부망을 찌르지 못하게 막는다.
 * - `http`/`https`만 허용한다. `file:`, `gopher:`, `ftp:` 등은 거부한다.
 * - 호스트 이름 자체(`localhost`, `*.local`, `*.internal`)를 먼저 거부한다.
 * - **DNS로 풀어 나온 모든 IP**를 검사한다. 하나라도 사설·루프백·링크로컬이면 거부한다
 *   (`internal.example.com → 10.0.0.5` 같은 DNS 리바인딩 표적을 막는다).
 * - 리다이렉트는 [PageFetcher]가 직접 따라가며 **매 홉마다** 이것을 다시 부른다.
 *
 * `crack.import.allow-private-hosts=true`면 IP·호스트 검사를 끈다. 테스트에서 로컬 서버를 쓰기 위한 것이고,
 * 운영에서는 켜지 않는다.
 */
@Component
open class SsrfGuard(private val properties: ImportProperties) {

    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * URL을 검사하고 정규화한 [URI]를 돌려준다.
     *
     * @throws BadRequestException 형식이 틀렸거나 막힌 대상일 때
     */
    open fun check(url: String): URI {
        val uri = parse(url)
        val host = uri.host ?: throw BadRequestException("주소에 호스트가 없습니다: $url")

        if (uri.userInfo != null) {
            throw BadRequestException("사용자 정보(user@host)가 붙은 주소는 쓸 수 없습니다")
        }
        if (properties.allowPrivateHosts) return uri

        if (isBlockedHostName(host)) {
            throw BadRequestException("내부 주소는 가져올 수 없습니다: $host")
        }
        val addresses = resolve(host)
        for (address in addresses) {
            if (isBlockedAddress(address)) {
                log.warn("SSRF 차단: host={}, ip={}", host, address.hostAddress)
                throw BadRequestException("내부 주소는 가져올 수 없습니다: $host (${address.hostAddress})")
            }
        }
        return uri
    }

    private fun parse(url: String): URI {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) throw BadRequestException("주소를 입력하세요")
        val uri = try {
            URI(trimmed)
        } catch (e: URISyntaxException) {
            throw BadRequestException("주소 형식이 올바르지 않습니다: $trimmed")
        }
        if (!uri.isAbsolute) throw BadRequestException("http:// 또는 https:// 로 시작하는 주소를 입력하세요")
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") {
            throw BadRequestException("http, https 주소만 가져올 수 있습니다: $scheme")
        }
        return uri
    }

    private fun resolve(host: String): List<InetAddress> =
        try {
            InetAddress.getAllByName(host).toList()
        } catch (e: UnknownHostException) {
            throw BadRequestException("주소를 찾을 수 없습니다: $host")
        }

    /** 호스트 이름만 보고 막는다. DNS를 풀기 전에 걸러 내는 1차 방어다. */
    fun isBlockedHostName(host: String): Boolean {
        val name = host.lowercase().trim().trimEnd('.').removeSurrounding("[", "]")
        if (name.isEmpty()) return true
        if (name == "localhost" || name.endsWith(".localhost")) return true
        if (name.endsWith(".local") || name.endsWith(".internal") || name.endsWith(".localdomain")) return true
        return false
    }

    /** 사설·루프백·링크로컬·와일드카드·멀티캐스트 IP인지. */
    fun isBlockedAddress(address: InetAddress): Boolean {
        if (address.isAnyLocalAddress || address.isLoopbackAddress ||
            address.isLinkLocalAddress || address.isSiteLocalAddress ||
            address.isMulticastAddress
        ) {
            return true
        }
        return when (address) {
            // isSiteLocalAddress가 놓치는 대역 (172.16/12의 일부 구현 차이, 공유 주소 100.64/10, 문서용 192.0.2/24 등)
            is Inet4Address -> {
                val b = address.address.map { it.toInt() and 0xFF }
                when {
                    b[0] == 10 -> true
                    b[0] == 127 -> true
                    b[0] == 172 && b[1] in 16..31 -> true
                    b[0] == 192 && b[1] == 168 -> true
                    b[0] == 169 && b[1] == 254 -> true
                    b[0] == 100 && b[1] in 64..127 -> true // 100.64/10 CGNAT
                    b[0] == 192 && b[1] == 0 && b[2] == 0 -> true // 192.0.0/24
                    b[0] == 0 -> true
                    b[0] >= 240 -> true // 240/4 예약
                    else -> false
                }
            }
            // fc00::/7 (유니크 로컬), ::ffff:0:0/96 로 감싼 IPv4, ::/128, ::1
            is Inet6Address -> {
                val b = address.address.map { it.toInt() and 0xFF }
                when {
                    (b[0] and 0xFE) == 0xFC -> true // fc00::/7
                    address.isIPv4CompatibleAddress -> true
                    b.take(10).all { it == 0 } && b[10] == 0xFF && b[11] == 0xFF -> {
                        // ::ffff:a.b.c.d → IPv4 규칙으로 다시 검사
                        isBlockedAddress(InetAddress.getByAddress(address.address.copyOfRange(12, 16)))
                    }
                    else -> false
                }
            }
            else -> false
        }
    }
}
