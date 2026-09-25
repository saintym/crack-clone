package com.crack.scenario.imports

import org.springframework.stereotype.Component
import java.net.URI

/** `<script>` 안에서 잘라 온 데이터 블록 하나. [text]는 원문 그대로다. */
data class DataBlock(val name: String, val text: String) {
    val length: Int get() = text.length
}

/** 무엇을 얼마나 잘랐는지. 분석 응답에 그대로 나간다 (DESIGN.md §11.1). */
data class Truncation(
    val bodyChars: Int = 0,
    val dataChars: Int = 0,
    val droppedBodyChars: Int = 0,
    val droppedBlocks: Int = 0,
) {
    val truncated: Boolean get() = droppedBodyChars > 0 || droppedBlocks > 0
}

/**
 * 추출 결과. LLM에 넘기는 것은 [context] 하나다.
 *
 * @property title `<title>` 값
 * @property bodyText 태그를 없앤 본문 (상한 적용 뒤)
 * @property dataBlocks `<script>` 안의 데이터 리터럴 (상한 적용 뒤)
 * @property imageUrls 절대 URL로 정규화한 이미지 주소, 중복 없음
 */
data class ExtractedPage(
    val url: String,
    val title: String,
    val bodyText: String,
    val dataBlocks: List<DataBlock>,
    val imageUrls: List<String>,
    val truncated: Truncation,
) {
    /** LLM에 주는 컨텍스트. 단계마다 같은 텍스트를 써서 프롬프트 접두사를 유지한다. */
    fun context(): String = buildString {
        append("## 페이지 주소\n").append(url).append('\n')
        if (title.isNotBlank()) append("\n## 페이지 제목\n").append(title).append('\n')
        if (dataBlocks.isNotEmpty()) {
            append("\n## 스크립트 데이터 (원문)\n")
            dataBlocks.forEach { block ->
                append("\n### ").append(block.name).append('\n')
                append("```js\n").append(block.text).append("\n```\n")
            }
        }
        if (bodyText.isNotBlank()) append("\n## 본문 텍스트\n").append(bodyText).append('\n')
        if (truncated.truncated) {
            append("\n## 잘린 부분\n")
            if (truncated.droppedBodyChars > 0) append("- 본문 뒤쪽 ").append(truncated.droppedBodyChars).append("자 생략\n")
            if (truncated.droppedBlocks > 0) append("- 데이터 블록 ").append(truncated.droppedBlocks).append("개 생략\n")
        }
    }
}

/**
 * HTML에서 LLM에 줄 세 덩어리를 뽑는다 (DESIGN.md §11.1).
 *
 * 1. **본문 텍스트**: 주석·`<script>`·`<style>`을 지우고 태그를 없앤 뒤 공백을 정리한다.
 * 2. **데이터 블록**: `<script>` 안의 **들여쓰기 없는** `const|let|var NAME = [...]`/`{...}`를 원문 그대로 잘라 온다.
 *    괄호 짝을 세며 문자열과 주석을 건너뛰므로 리터럴 안의 `}`에 속지 않는다.
 *    함수 안의 지역 변수는 들여쓰기가 있어 자연히 빠진다.
 * 3. **이미지 URL**: `src`/`srcset`/`href`와 데이터 블록 안의 이미지 주소. 문서 주소 기준 절대 URL로 정규화한다.
 *
 * 합계가 `crack.import.max-context-chars`를 넘으면 **데이터 블록을 먼저 남기고** 본문을 앞에서부터 잘라 쓴다.
 * base64 `data:` 블록(엠블럼 이미지 등)은 토큰만 먹으므로 버린다.
 */
@Component
class HtmlExtractor(private val properties: ImportProperties) {

    fun extract(page: FetchedPage): ExtractedPage = extract(page.url, page.html)

    fun extract(url: String, html: String) : ExtractedPage {
        val withoutComments = html.replace(HTML_COMMENT, " ")
        val scripts = SCRIPT.findAll(withoutComments).map { it.groupValues[1] }.toList()
        val title = TITLE.find(withoutComments)?.groupValues?.get(1)?.let { unescape(it).trim() }.orEmpty()

        val rawBody = plainText(withoutComments)
        val rawBlocks = scripts.flatMap { dataBlocks(it) }
        val images = imageUrls(url, withoutComments, rawBlocks)

        val (blocks, body, truncation) = applyLimits(rawBlocks, rawBody)
        return ExtractedPage(url, title, body, blocks, images, truncation)
    }

    // --- 본문 텍스트 ---

    /** 태그를 없애고 공백을 정리한 텍스트. 블록 요소는 줄바꿈으로 바꾼다. */
    fun plainText(html: String): String {
        val stripped = html
            .replace(SCRIPT, "\n")
            .replace(STYLE, "\n")
            .replace(BLOCK_TAG, "\n")
            .replace(TAG, " ")
        return unescape(stripped)
            .lineSequence()
            .map { it.replace(SPACES, " ").trim() }
            .joinToString("\n")
            .replace(BLANK_LINES, "\n\n")
            .trim()
    }

    // --- 데이터 블록 ---

    /** 스크립트 하나에서 최상위 데이터 리터럴을 잘라 온다. */
    fun dataBlocks(script: String): List<DataBlock> {
        val result = mutableListOf<DataBlock>()
        for (match in DECLARATION.findAll(script)) {
            val open = match.range.last
            val close = matchBracket(script, open) ?: continue
            val text = script.substring(match.range.first, close + 1)
            result += DataBlock(match.groupValues[1], text)
        }
        return result
    }

    /**
     * [open] 위치의 `[`/`{`와 짝이 되는 닫는 괄호 위치. 문자열(`'`, `"`, 백틱)과 주석 안은 세지 않는다.
     * 짝을 찾지 못하면 null.
     */
    private fun matchBracket(text: String, open: Int): Int? {
        var depth = 0
        var i = open
        while (i < text.length) {
            when (val c = text[i]) {
                '[', '{', '(' -> depth++
                ']', '}', ')' -> {
                    depth--
                    if (depth == 0) return i
                }
                '\'', '"', '`' -> {
                    i = skipString(text, i, c) ?: return null
                }
                '/' -> {
                    if (i + 1 < text.length && text[i + 1] == '/') {
                        val nl = text.indexOf('\n', i)
                        if (nl < 0) return null
                        i = nl
                    } else if (i + 1 < text.length && text[i + 1] == '*') {
                        val end = text.indexOf("*/", i + 2)
                        if (end < 0) return null
                        i = end + 1
                    }
                }
            }
            i++
        }
        return null
    }

    /** 닫는 인용부호 위치. 이스케이프(`\"`)를 건너뛴다. 닫히지 않으면 null. */
    private fun skipString(text: String, start: Int, quote: Char): Int? {
        var i = start + 1
        while (i < text.length) {
            when (text[i]) {
                '\\' -> i++
                quote -> return i
                '\n' -> if (quote != '`') return null // 백틱이 아니면 줄을 넘지 않는다
            }
            i++
        }
        return null
    }

    // --- 이미지 URL ---

    fun imageUrls(baseUrl: String, html: String, blocks: List<DataBlock>): List<String> {
        val base = runCatching { URI(baseUrl) }.getOrNull()
        val found = LinkedHashSet<String>()

        fun add(raw: String) {
            val value = raw.trim().trim('"', '\'')
            if (value.isEmpty() || value.startsWith("data:") || value.startsWith("#")) return
            val absolute = absolutize(base, value) ?: return
            if (!isImagePath(absolute)) return
            found += absolute
        }

        ATTR_URL.findAll(html).forEach { m ->
            val attr = m.groupValues[1].lowercase()
            val value = m.groupValues[2]
            if (attr == "srcset") {
                value.split(',').forEach { add(it.trim().substringBefore(' ')) }
            } else {
                add(value)
            }
        }
        blocks.forEach { block ->
            URL_IN_TEXT.findAll(block.text).forEach { add(it.value) }
        }
        return found.toList()
    }

    private fun absolutize(base: URI?, value: String): String? {
        val uri = runCatching { URI(value) }.getOrNull() ?: return null
        val resolved = if (uri.isAbsolute) uri else base?.resolve(uri) ?: return null
        val scheme = resolved.scheme?.lowercase() ?: return null
        if (scheme != "http" && scheme != "https") return null
        return resolved.toString()
    }

    private fun isImagePath(url: String): Boolean {
        val path = url.substringBefore('?').substringBefore('#').lowercase()
        return IMAGE_EXTENSIONS.any { path.endsWith(it) }
    }

    // --- 상한 ---

    /**
     * 상한을 적용한다. **데이터 블록을 먼저 남기고** 본문을 앞에서부터 잘라 쓴다.
     * base64 블록과 블록 하나의 상한(`max-data-block-chars`)을 넘는 꼬리는 버린다.
     */
    private fun applyLimits(rawBlocks: List<DataBlock>, rawBody: String): Triple<List<DataBlock>, String, Truncation> {
        var dropped = 0
        val capped = mutableListOf<DataBlock>()
        for (block in rawBlocks) {
            if (BASE64_DATA in block.text) { // 엠블럼 등 base64 이미지 덩어리. 설정 정보가 없다
                dropped++
                continue
            }
            if (block.length > properties.maxDataBlockChars) {
                capped += DataBlock(block.name, block.text.take(properties.maxDataBlockChars) + "\n/* …(이하 생략) */")
            } else {
                capped += block
            }
        }

        val budget = properties.maxContextChars
        val kept = mutableListOf<DataBlock>()
        var dataChars = 0
        for (block in capped) {
            if (dataChars + block.length > budget) {
                dropped++
                continue
            }
            kept += block
            dataChars += block.length
        }

        val bodyBudget = (budget - dataChars).coerceAtLeast(0)
        val body = if (rawBody.length <= bodyBudget) rawBody else rawBody.take(bodyBudget)
        return Triple(
            kept,
            body,
            Truncation(
                bodyChars = body.length,
                dataChars = dataChars,
                droppedBodyChars = rawBody.length - body.length,
                droppedBlocks = dropped,
            ),
        )
    }

    // --- HTML 엔티티 ---

    fun unescape(text: String): String {
        if ('&' !in text) return text
        return ENTITY.replace(text) { m ->
            val body = m.groupValues[1]
            when {
                body.startsWith("#x") || body.startsWith("#X") ->
                    body.drop(2).toIntOrNull(16)?.let { codePoint(it) } ?: m.value
                body.startsWith("#") ->
                    body.drop(1).toIntOrNull()?.let { codePoint(it) } ?: m.value
                else -> NAMED_ENTITIES[body.lowercase()] ?: m.value
            }
        }
    }

    private fun codePoint(value: Int): String? =
        if (value in 1..0x10FFFF) String(Character.toChars(value)) else null

    companion object {
        private val HTML_COMMENT = Regex("""<!--[\s\S]*?-->""")
        private val SCRIPT = Regex("""<script\b[^>]*>([\s\S]*?)</script\s*>""", RegexOption.IGNORE_CASE)
        private val STYLE = Regex("""<style\b[^>]*>[\s\S]*?</style\s*>""", RegexOption.IGNORE_CASE)
        private val TITLE = Regex("""<title[^>]*>([\s\S]*?)</title\s*>""", RegexOption.IGNORE_CASE)
        private val BLOCK_TAG = Regex(
            """</?(?:p|div|br|li|ul|ol|tr|td|th|table|section|article|header|footer|nav|h[1-6]|hr|blockquote|pre|dd|dt|dl|figure|figcaption|main|aside|option)\b[^>]*>""",
            RegexOption.IGNORE_CASE,
        )
        private val TAG = Regex("""<[^>]*>""")
        private val SPACES = Regex("""[ \t 　]+""")
        private val BLANK_LINES = Regex("""\n{3,}""")

        /** 줄 맨 앞(들여쓰기 없음)의 `const|let|var NAME = [` 또는 `{`. 마지막 문자가 여는 괄호다. */
        private val DECLARATION = Regex("""(?m)^(?:const|let|var)\s+([A-Za-z_$][A-Za-z0-9_$]*)\s*=\s*[\[{]""")

        private val ATTR_URL = Regex("""\b(src|srcset|href|data-src|content)\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
        private val URL_IN_TEXT = Regex("""https?://[^\s"'`<>()\\]+""")
        private val IMAGE_EXTENSIONS = listOf(".webp", ".png", ".jpg", ".jpeg", ".gif", ".avif", ".bmp", ".svg")

        private const val BASE64_DATA = ";base64,"

        private val ENTITY = Regex("""&(#[0-9]{1,7}|#[xX][0-9A-Fa-f]{1,6}|[A-Za-z][A-Za-z0-9]{1,31});""")
        private val NAMED_ENTITIES = mapOf(
            "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'",
            "nbsp" to " ", "middot" to "·", "hellip" to "…", "mdash" to "—", "ndash" to "–",
            "laquo" to "«", "raquo" to "»", "ldquo" to "“", "rdquo" to "”",
            "lsquo" to "‘", "rsquo" to "’", "times" to "×", "copy" to "©", "reg" to "®",
        )
    }
}
