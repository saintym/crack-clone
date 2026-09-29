package com.crack.scenario.imports.guided

import com.crack.scenario.imports.ImportFormatException
import com.crack.scenario.imports.ImportOutputParser
import com.crack.scenario.imports.ImportQuestion
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper

/** 질문 라운드 응답 한 건. 다음 질문이거나(`done=false`) 준비 완료(`done=true`)다. */
data class CreateRoundResult(
    val title: String,
    val suggestedName: String,
    val done: Boolean,
    val questions: List<ImportQuestion>,
    val preview: CreatePreview?,
)

/**
 * 질문 라운드 JSON 파서 (DESIGN.md §11.6).
 *
 * 앞뒤 잡소리와 코드 펜스는 [ImportOutputParser.jsonObject]가 걷어낸다.
 * **질문이 하나도 없으면 done으로 본다** — 물을 것이 없다는 뜻이고, 빈 화면을 보여 줄 수는 없다.
 */
object CreateOutputParser {

    fun parse(mapper: ObjectMapper, raw: String, fallbackTitle: String): CreateRoundResult {
        val json = ImportOutputParser.jsonObject(raw) ?: throw ImportFormatException("질문 응답이 JSON이 아닙니다")
        val node = try {
            mapper.readTree(json)
        } catch (e: Exception) {
            throw ImportFormatException("질문 응답 JSON을 읽지 못했습니다: ${e.message}")
        }
        val title = node.path("title").asText("").trim().ifBlank { fallbackTitle }
        val suggested = node.path("suggestedName").asText("").trim().ifBlank { title }
        val questions = node.path("questions").mapIndexedNotNull { index, q -> q.toQuestion(index) }
            .distinctBy { it.id }
        val preview = node.path("preview").takeIf { it.isObject }?.toPreview(title)
        val done = node.path("done").asBoolean(false) || questions.isEmpty()
        return CreateRoundResult(
            title = title,
            suggestedName = suggested,
            done = done,
            questions = if (done) emptyList() else questions,
            preview = if (done) preview else null,
        )
    }

    private fun JsonNode.toQuestion(index: Int): ImportQuestion? {
        val text = path("text").asText("").trim()
        if (text.isEmpty()) return null
        val id = path("id").asText("").trim().ifEmpty { "q${index + 1}" }
        return ImportQuestion(id, text, path("placeholder").asText("").trim())
    }

    private fun JsonNode.toPreview(title: String) = CreatePreview(
        title = title,
        world = path("world").asText("").trim(),
        protagonist = path("protagonist").asText("").trim(),
        opening = path("opening").asText("").trim(),
        characters = path("characters").mapNotNull { it.toCharacter() }.distinctBy { it.name },
    )

    private fun JsonNode.toCharacter(): CreateCharacter? {
        val name = path("name").asText("").trim()
        if (name.isEmpty()) return null
        return CreateCharacter(
            name = name,
            role = path("role").asText("").trim(),
            note = path("note").asText("").trim(),
        )
    }
}
