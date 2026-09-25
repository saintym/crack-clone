package com.crack.scenario.imports

import com.crack.global.exception.BadRequestException
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.nio.charset.Charset
import java.nio.charset.IllegalCharsetNameException
import java.nio.charset.StandardCharsets
import java.nio.charset.UnsupportedCharsetException

/** 내려받은 페이지 하나. [url]은 리다이렉트를 따라간 뒤의 최종 주소다. */
data class FetchedPage(val url: String, val contentType: String, val html: String)

/**
 * HTML 한 장을 안전하게 내려받는다 (DESIGN.md §11.1).
 *
 * - [SsrfGuard]로 **매 홉**을 검사한다. 리다이렉트를 자동으로 따라가면 검사를 건너뛰게 되므로
 *   `instanceFollowRedirects=false`로 두고 직접 따라간다.
 * - 상한: `crack.import.max-bytes`(기본 5MB), `crack.import.timeout-seconds`(기본 20초, 연결·읽기 각각).
 * - `Content-Type`은 `text/html`·`application/xhtml+xml`만 받는다.
 * - 인코딩은 `charset` → `<meta charset>` → UTF-8 순으로 정한다.
 */
@Component
class PageFetcher(
    private val guard: SsrfGuard,
    private val properties: ImportProperties,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun fetch(rawUrl: String): FetchedPage {
        var uri = guard.check(rawUrl)
        var hops = 0
        while (true) {
            val connection = open(uri)
            try {
                val status = connection.responseCode
                if (status in 300..399) {
                    val location = connection.getHeaderField("Location")
                        ?: throw BadRequestException("리다이렉트 응답에 Location이 없습니다 ($status)")
                    if (++hops > properties.maxRedirects) {
                        throw BadRequestException("리다이렉트가 너무 많습니다 (${properties.maxRedirects}회 초과)")
                    }
                    // 상대 경로 Location도 있으므로 현재 주소를 기준으로 합치고 다시 검사한다
                    val next = runCatching { uri.resolve(location) }.getOrElse {
                        throw BadRequestException("리다이렉트 주소가 올바르지 않습니다: $location")
                    }
                    log.debug("리다이렉트 {} → {}", uri, next)
                    uri = guard.check(next.toString())
                    continue
                }
                if (status != HttpURLConnection.HTTP_OK) {
                    throw BadRequestException("페이지를 가져오지 못했습니다 (HTTP $status)")
                }
                val contentType = connection.contentType?.trim().orEmpty()
                requireHtml(contentType)
                val declaredLength = connection.contentLengthLong
                if (declaredLength > properties.maxBytes) {
                    throw BadRequestException("페이지가 너무 큽니다 (${declaredLength}바이트, 상한 ${properties.maxBytes})")
                }
                val bytes = connection.inputStream.use { readLimited(it) }
                val charset = charsetOf(contentType, bytes)
                return FetchedPage(uri.toString(), contentType, String(bytes, charset))
            } finally {
                connection.disconnect()
            }
        }
    }

    private fun open(uri: URI): HttpURLConnection {
        val connection = URL(uri.toString()).openConnection() as HttpURLConnection
        connection.instanceFollowRedirects = false // 매 홉을 직접 검사한다
        connection.connectTimeout = properties.timeoutSeconds * 1000
        connection.readTimeout = properties.timeoutSeconds * 1000
        connection.requestMethod = "GET"
        connection.setRequestProperty("Accept", "text/html,application/xhtml+xml")
        connection.setRequestProperty("Accept-Encoding", "identity") // 상한 검사를 압축 해제 뒤 크기로 흔들리지 않게
        connection.setRequestProperty("User-Agent", USER_AGENT)
        return connection
    }

    private fun requireHtml(contentType: String) {
        val mime = contentType.substringBefore(';').trim().lowercase()
        if (mime.isEmpty()) throw BadRequestException("Content-Type이 없습니다. HTML 페이지만 가져올 수 있습니다")
        if (mime != "text/html" && mime != "application/xhtml+xml") {
            throw BadRequestException("HTML 페이지만 가져올 수 있습니다 (Content-Type: $mime)")
        }
    }

    /** 상한까지만 읽는다. 상한을 넘으면 예외다(잘라서 쓰면 데이터 블록이 깨진 채 LLM에 들어간다). */
    private fun readLimited(input: InputStream): ByteArray {
        val limit = properties.maxBytes
        val buffer = ByteArray(16 * 1024)
        val out = java.io.ByteArrayOutputStream()
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            if (total > limit) throw BadRequestException("페이지가 너무 큽니다 (상한 ${limit}바이트)")
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }

    /** `Content-Type`의 charset → `<meta charset>` → UTF-8. */
    private fun charsetOf(contentType: String, bytes: ByteArray): Charset {
        headerCharset(contentType)?.let { return it }
        val head = String(bytes, 0, minOf(bytes.size, 4096), StandardCharsets.ISO_8859_1)
        META_CHARSET.find(head)?.groupValues?.get(1)?.let { name ->
            toCharset(name)?.let { return it }
        }
        return StandardCharsets.UTF_8
    }

    private fun headerCharset(contentType: String): Charset? =
        CONTENT_TYPE_CHARSET.find(contentType)?.groupValues?.get(1)?.let { toCharset(it) }

    private fun toCharset(name: String): Charset? =
        try {
            Charset.forName(name.trim().trim('"', '\''))
        } catch (e: IllegalCharsetNameException) {
            null
        } catch (e: UnsupportedCharsetException) {
            null
        }

    companion object {
        const val USER_AGENT = "crack-clone/0.1 (scenario import)"
        private val CONTENT_TYPE_CHARSET = Regex("""charset\s*=\s*([^;\s]+)""", RegexOption.IGNORE_CASE)
        private val META_CHARSET = Regex("""<meta[^>]+charset\s*=\s*["']?([A-Za-z0-9_\-]+)""", RegexOption.IGNORE_CASE)
    }
}
