package com.crack.image

import java.net.URI
import java.net.URISyntaxException

/** 이미지 카탈로그 항목 하나 (DESIGN.md §8.5). [description]은 없으면 빈 문자열이다. */
data class ImageEntry(val tag: String, val url: String, val description: String)

/**
 * `images.md` 파서 (DESIGN.md §8.5).
 *
 * ```markdown
 * - 설월_미소: https://example.com/a.webp | 설월이 옅게 웃는 모습
 * ```
 * - `- `나 `* `로 시작하는 줄만 읽는다. 제목, 빈 줄, 안내문은 무시한다.
 * - HTML 주석(`<!-- … -->`) 안은 읽지 않는다. 항목을 잠시 끄거나 예시를 적을 때 쓴다.
 * - 태그는 첫 `:` 앞이다. 공백과 `:`, `{`, `}`, `|`는 쓸 수 없다(`{{img:태그}}`로 그대로 쓰기 때문).
 * - URL은 첫 `|` 앞, 설명은 그 뒤(선택). `http`/`https`가 아니면 그 줄을 버린다.
 * - 같은 태그는 위에 있는 것이 우선한다.
 */
object ImageCatalogParser {
    private val ITEM = Regex("""^\s*[-*]\s+(.*)$""")
    private val TAG = Regex("""^[^\s:{}|]+$""")
    private val COMMENT = Regex("""<!--[\s\S]*?(-->|$)""")

    fun parse(text: String): List<ImageEntry> {
        val seen = HashSet<String>()
        return text.replace(COMMENT, "").lineSequence()
            .mapNotNull { parseLine(it) }
            .filter { seen.add(it.tag) }
            .toList()
    }

    /** 항목 줄 하나. 형식에 맞지 않거나 URL이 허용되지 않으면 null. */
    fun parseLine(line: String): ImageEntry? {
        val body = ITEM.find(line)?.groupValues?.get(1) ?: return null
        val colon = body.indexOf(':')
        if (colon <= 0) return null
        val tag = body.substring(0, colon).trim()
        if (!TAG.matches(tag)) return null

        val rest = body.substring(colon + 1)
        val bar = rest.indexOf('|')
        val url = (if (bar < 0) rest else rest.substring(0, bar)).trim()
        val description = if (bar < 0) "" else rest.substring(bar + 1).trim()
        if (!isAllowedUrl(url)) return null
        return ImageEntry(tag, url, description)
    }

    /** `http`/`https`이고 호스트가 있는 절대 URL만 허용한다. 공백이나 `<`, `"` 같은 문자는 URI 파싱에서 걸러진다. */
    fun isAllowedUrl(url: String): Boolean {
        if (url.isEmpty()) return false
        val uri = try {
            URI(url)
        } catch (e: URISyntaxException) {
            return false
        }
        val scheme = uri.scheme?.lowercase() ?: return false
        return (scheme == "http" || scheme == "https") && !uri.host.isNullOrEmpty()
    }
}
