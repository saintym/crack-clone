package com.crack.scenario.imports

import com.crack.global.exception.BadRequestException
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.net.URI
import java.nio.charset.Charset

/**
 * 내려받기 (DESIGN.md §11.1): 상한, Content-Type, 인코딩, **리다이렉트 매 홉 검사**.
 *
 * 로컬 서버(127.0.0.1)를 쓰므로 검사를 그냥 끄면 "홉마다 검사한다"를 증명할 수 없다.
 * 그래서 [RecordingGuard]로 어느 주소를 검사했는지 기록하고, 정해 둔 주소만 막는다.
 */
class PageFetcherTest {

    /** 검사한 주소를 기록하고 [blocked]에 든 호스트만 막는 가드. */
    private class RecordingGuard(
        properties: ImportProperties,
        val blocked: Set<String> = emptySet(),
    ) : SsrfGuard(properties) {
        val checked = mutableListOf<String>()

        override fun check(url: String): URI {
            val uri = super.check(url)
            checked += url
            if (uri.host in blocked) throw BadRequestException("내부 주소는 가져올 수 없습니다: ${uri.host}")
            return uri
        }
    }

    private lateinit var server: HttpServer
    private var port = 0

    @BeforeEach
    fun setUp() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        port = server.address.port
        server.start()
    }

    @AfterEach
    fun tearDown() {
        server.stop(0)
    }

    private fun properties(maxBytes: Long = 5L * 1024 * 1024, maxRedirects: Int = 5) =
        ImportProperties(maxBytes = maxBytes, maxRedirects = maxRedirects, timeoutSeconds = 5, allowPrivateHosts = true)

    private fun url(path: String) = "http://127.0.0.1:$port$path"

    private fun serve(path: String, contentType: String?, body: ByteArray, status: Int = 200, location: String? = null) {
        server.createContext(path) { exchange: HttpExchange ->
            contentType?.let { exchange.responseHeaders.add("Content-Type", it) }
            location?.let { exchange.responseHeaders.add("Location", it) }
            exchange.sendResponseHeaders(status, if (status in 300..399) -1 else body.size.toLong())
            if (status !in 300..399) exchange.responseBody.use { it.write(body) }
            exchange.close()
        }
    }

    @Test
    fun `HTML을 내려받고 최종 주소를 돌려준다`() {
        serve("/a.html", "text/html; charset=UTF-8", "<h1>안녕</h1>".toByteArray())
        val fetcher = PageFetcher(RecordingGuard(properties()), properties())

        val page = fetcher.fetch(url("/a.html"))

        assertThat(page.html).contains("안녕")
        assertThat(page.url).isEqualTo(url("/a.html"))
    }

    @Test
    fun `Content-Type의 charset을 따른다`() {
        val euckr = Charset.forName("EUC-KR")
        serve("/euc.html", "text/html; charset=EUC-KR", "<p>한글</p>".toByteArray(euckr))
        val fetcher = PageFetcher(RecordingGuard(properties()), properties())

        assertThat(fetcher.fetch(url("/euc.html")).html).contains("한글")
    }

    @Test
    fun `charset이 없으면 meta charset을 본다`() {
        val euckr = Charset.forName("EUC-KR")
        val html = """<html><head><meta charset="euc-kr"></head><body>한글</body></html>"""
        serve("/meta.html", "text/html", html.toByteArray(euckr))
        val fetcher = PageFetcher(RecordingGuard(properties()), properties())

        assertThat(fetcher.fetch(url("/meta.html")).html).contains("한글")
    }

    @Test
    fun `HTML이 아니면 거부한다`() {
        serve("/a.json", "application/json", """{"a":1}""".toByteArray())
        val fetcher = PageFetcher(RecordingGuard(properties()), properties())

        assertThatThrownBy { fetcher.fetch(url("/a.json")) }
            .isInstanceOf(BadRequestException::class.java)
            .hasMessageContaining("HTML")
    }

    @Test
    fun `상한을 넘는 페이지를 거부한다`() {
        serve("/big.html", "text/html", ByteArray(4096) { 'a'.code.toByte() })
        val fetcher = PageFetcher(RecordingGuard(properties(maxBytes = 1024)), properties(maxBytes = 1024))

        assertThatThrownBy { fetcher.fetch(url("/big.html")) }
            .isInstanceOf(BadRequestException::class.java)
            .hasMessageContaining("너무 큽니다")
    }

    @Test
    fun `리다이렉트를 따라가며 홉마다 검사한다`() {
        serve("/from.html", null, ByteArray(0), status = 302, location = url("/to.html"))
        serve("/to.html", "text/html", "<p>도착</p>".toByteArray())
        val guard = RecordingGuard(properties())
        val fetcher = PageFetcher(guard, properties())

        val page = fetcher.fetch(url("/from.html"))

        assertThat(page.html).contains("도착")
        assertThat(page.url).isEqualTo(url("/to.html"))
        assertThat(guard.checked).containsExactly(url("/from.html"), url("/to.html"))
    }

    @Test
    fun `리다이렉트가 내부 주소를 가리키면 막는다`() {
        serve("/evil.html", null, ByteArray(0), status = 302, location = "http://169.254.169.254/latest/meta-data")
        val guard = RecordingGuard(properties(), blocked = setOf("169.254.169.254"))
        val fetcher = PageFetcher(guard, properties())

        assertThatThrownBy { fetcher.fetch(url("/evil.html")) }
            .isInstanceOf(BadRequestException::class.java)
            .hasMessageContaining("내부 주소")
        assertThat(guard.checked).hasSize(2) // 첫 홉은 통과, 두 번째 홉에서 막혔다
    }

    @Test
    fun `상대 경로 Location도 현재 주소를 기준으로 합친다`() {
        serve("/rel.html", null, ByteArray(0), status = 301, location = "/dest.html")
        serve("/dest.html", "text/html", "<p>상대</p>".toByteArray())
        val fetcher = PageFetcher(RecordingGuard(properties()), properties())

        assertThat(fetcher.fetch(url("/rel.html")).url).isEqualTo(url("/dest.html"))
    }

    @Test
    fun `리다이렉트가 너무 많으면 거부한다`() {
        serve("/loop.html", null, ByteArray(0), status = 302, location = url("/loop.html"))
        val fetcher = PageFetcher(RecordingGuard(properties(maxRedirects = 2)), properties(maxRedirects = 2))

        assertThatThrownBy { fetcher.fetch(url("/loop.html")) }
            .isInstanceOf(BadRequestException::class.java)
            .hasMessageContaining("리다이렉트")
    }

    @Test
    fun `200이 아닌 응답을 거부한다`() {
        serve("/missing.html", "text/html", "없음".toByteArray(), status = 404)
        val fetcher = PageFetcher(RecordingGuard(properties()), properties())

        assertThatThrownBy { fetcher.fetch(url("/missing.html")) }
            .isInstanceOf(BadRequestException::class.java)
            .hasMessageContaining("HTTP 404")
    }
}
