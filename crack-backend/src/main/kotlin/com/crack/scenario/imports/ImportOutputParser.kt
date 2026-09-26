package com.crack.scenario.imports

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper

/** LLM 출력 형식이 계약과 다를 때. 어느 단계에서 깨졌는지 문구에 담는다. */
class ImportFormatException(message: String) : RuntimeException(message)

/**
 * LLM 출력 파서 (DESIGN.md §11.3).
 *
 * 태그(`<world>`, `<character name="…">`)와 분석 JSON을 느슨하게 읽는다.
 * LLM이 앞뒤에 말을 붙이거나 코드 펜스로 감싸는 일이 흔하므로 그것까지 견딘다.
 */
object ImportOutputParser {

    private val CHARACTER = Regex(
        """<character\b([^>]*)>([\s\S]*?)</character\s*>""",
        RegexOption.IGNORE_CASE,
    )
    private val NAME_ATTR = Regex("""name\s*=\s*["']?([^"'>]+)["']?""", RegexOption.IGNORE_CASE)

    /** 첫 `<name>…</name>` 본문(앞뒤 공백·코드 펜스 제거). 없으면 null. */
    fun tag(text: String, name: String): String? {
        val regex = Regex(
            """<${Regex.escape(name)}(?:\s[^>]*)?>([\s\S]*?)</${Regex.escape(name)}\s*>""",
            RegexOption.IGNORE_CASE,
        )
        return regex.find(text)?.groupValues?.get(1)?.let { ImportDocs.clean(it) }?.takeIf { it.isNotBlank() }
    }

    fun requireTag(text: String, name: String, step: String): String =
        tag(text, name) ?: throw ImportFormatException("$step 단계 응답에 <$name>이 없습니다")

    /** `<character name="설월">…</character>` 목록. 이름 속성이 없으면 문서의 `- **이름**:`에서 찾는다. */
    fun characters(text: String): List<Pair<String, String>> =
        CHARACTER.findAll(text).mapNotNull { m ->
            val body = ImportDocs.clean(m.groupValues[2])
            if (body.isBlank()) return@mapNotNull null
            val name = NAME_ATTR.find(m.groupValues[1])?.groupValues?.get(1)?.trim()
                ?: com.crack.memory.docs.MemoryDocs.parseField(body, "이름")?.trim()
            if (name.isNullOrBlank()) null else name to body
        }.toList()

    // --- 분석 JSON ---

    /**
     * 분석 응답 JSON을 읽는다. 앞뒤 잡소리와 코드 펜스를 걷어내고 첫 `{`부터 마지막 `}`까지만 본다.
     * 필수 값이 없으면 [fallbackTitle]과 페이지 제목으로 메꾼다.
     */
    fun parseAnalysis(mapper: ObjectMapper, raw: String, fallbackTitle: String): ImportAnalysis {
        val json = jsonObject(raw) ?: throw ImportFormatException("분석 응답이 JSON이 아닙니다")
        val node = try {
            mapper.readTree(json)
        } catch (e: Exception) {
            throw ImportFormatException("분석 응답 JSON을 읽지 못했습니다: ${e.message}")
        }
        val title = node.path("title").asText("").ifBlank { fallbackTitle }
        val suggested = node.path("suggestedName").asText("").ifBlank { title }

        val characters = node.path("characters").mapNotNull { it.toCharacter() }
            .distinctBy { it.name }
        val questions = node.path("questions").mapIndexedNotNull { index, q -> q.toQuestion(index) }
            .distinctBy { it.id }

        return ImportAnalysis(
            title = title.trim(),
            suggestedName = suggested.trim(),
            characters = characters,
            questions = questions,
        )
    }

    private fun JsonNode.toCharacter(): ImportCharacter? {
        val name = path("name").asText("").trim()
        if (name.isEmpty()) return null
        val imageUrl = path("imageUrl").asText("").trim()
        return ImportCharacter(
            name = name,
            alias = path("alias").asText("").trim(),
            org = path("org").asText("").trim(),
            imageUrl = if (imageUrl.startsWith("http://") || imageUrl.startsWith("https://")) imageUrl else "",
        )
    }

    private fun JsonNode.toQuestion(index: Int): ImportQuestion? {
        val text = path("text").asText("").trim()
        if (text.isEmpty()) return null
        val id = path("id").asText("").trim().ifEmpty { "q${index + 1}" }
        return ImportQuestion(id, text, path("placeholder").asText("").trim())
    }

    /** 첫 `{`부터 짝이 맞는 마지막 `}`까지. 없으면 null. */
    fun jsonObject(raw: String): String? {
        val text = ImportDocs.clean(raw)
        val start = text.indexOf('{')
        if (start < 0) return null
        val end = text.lastIndexOf('}')
        if (end <= start) return null
        return text.substring(start, end + 1)
    }
}
