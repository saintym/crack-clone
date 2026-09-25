package com.crack.scenario.imports

import com.crack.global.exception.BadRequestException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.net.InetAddress

/**
 * SSRF 방어 (DESIGN.md §11.1).
 *
 * 리터럴 IP만 쓴다. `InetAddress.getAllByName("127.0.0.1")`은 DNS를 타지 않으므로 테스트가 네트워크에 매달리지 않는다.
 */
class SsrfGuardTest {

    private val guard = SsrfGuard(ImportProperties())

    @ParameterizedTest
    @ValueSource(
        strings = [
            "http://127.0.0.1/a.html",
            "http://127.1.2.3/a.html",
            "https://10.0.0.5/a.html",
            "https://172.16.0.1/a.html",
            "https://172.31.255.255/a.html",
            "https://192.168.1.1/a.html",
            "http://169.254.169.254/latest/meta-data",
            "http://0.0.0.0/a.html",
            "http://[::1]/a.html",
            "http://[fc00::1]/a.html",
            "http://[fe80::1]/a.html",
            "http://100.64.0.1/a.html",
            "http://localhost:8080/a.html",
            "http://LOCALHOST/a.html",
            "http://foo.localhost/a.html",
            "http://db.internal/a.html",
            "http://printer.local/a.html",
        ],
    )
    fun `사설·루프백·링크로컬 주소를 막는다`(url: String) {
        assertThatThrownBy { guard.check(url) }
            .isInstanceOf(BadRequestException::class.java)
            .hasMessageContaining("내부 주소")
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "file:///etc/passwd",
            "ftp://example.com/a",
            "gopher://example.com/a",
            "jar:http://example.com/a!/b",
        ],
    )
    fun `http https가 아닌 스킴을 막는다`(url: String) {
        assertThatThrownBy { guard.check(url) }.isInstanceOf(BadRequestException::class.java)
    }

    @Test
    fun `사용자 정보가 붙은 주소를 막는다`() {
        assertThatThrownBy { guard.check("http://admin@8.8.8.8/a.html") }
            .isInstanceOf(BadRequestException::class.java)
            .hasMessageContaining("사용자 정보")
    }

    @Test
    fun `상대 주소와 빈 주소를 막는다`() {
        assertThatThrownBy { guard.check("/a.html") }.isInstanceOf(BadRequestException::class.java)
        assertThatThrownBy { guard.check("   ") }.isInstanceOf(BadRequestException::class.java)
    }

    @Test
    fun `공개 IP는 통과한다`() {
        val uri = guard.check("https://8.8.8.8/a.html")
        assertThat(uri.host).isEqualTo("8.8.8.8")
    }

    @Test
    fun `IPv4를 감싼 IPv6도 IPv4 규칙으로 막는다`() {
        assertThat(guard.isBlockedAddress(InetAddress.getByName("::ffff:127.0.0.1"))).isTrue()
        assertThat(guard.isBlockedAddress(InetAddress.getByName("::ffff:10.0.0.1"))).isTrue()
        assertThat(guard.isBlockedAddress(InetAddress.getByName("::ffff:8.8.8.8"))).isFalse()
    }

    @Test
    fun `allow-private-hosts를 켜면 검사를 건너뛴다 (테스트 전용)`() {
        val relaxed = SsrfGuard(ImportProperties(allowPrivateHosts = true))
        assertThat(relaxed.check("http://127.0.0.1:1234/a.html").port).isEqualTo(1234)
        // 스킴 검사는 끄지 않는다
        assertThatThrownBy { relaxed.check("file:///etc/passwd") }.isInstanceOf(BadRequestException::class.java)
    }
}
