package com.crack.prompt.api

import com.crack.ai.dto.MessageRole
import com.crack.prompt.contributor.PromptSlot
import com.crack.prompt.service.AssembledPrompt
import com.crack.prompt.service.PromptAssembler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * `GET /api/stories/{id}/prompt-preview` (DESIGN.md §6.3). 섹션별 크기와 전문을 돌려준다(포트폴리오 지표와 디버깅용).
 *
 * 아무것도 저장하지 않는다. [input]을 주면 그 내용을 이번 유저 입력으로 보고 조립한다(다음 전송 미리 보기).
 */
@RestController
class PromptPreviewController(private val promptAssembler: PromptAssembler) {

    @GetMapping("/api/stories/{storyId}/prompt-preview")
    fun preview(
        @PathVariable storyId: Long,
        @RequestParam(required = false) input: String?,
    ): PromptPreviewResponse = PromptPreviewResponse.of(promptAssembler.assemble(storyId, pendingInput = input))
}

data class PromptPreviewResponse(
    val storyId: Long,
    val totalChars: Int,
    val systemChars: Int,
    val messageChars: Int,
    val sections: List<Section>,
    val activeCharacters: List<String>,
    /** 이번에 발동한 키워드북 항목 제목(§8.3, 우선순위 순) */
    val activeKeywords: List<String>,
    val rawWindow: RawWindowView,
    val systemPrompt: String,
    val messages: List<Message>,
) {
    data class Section(val slot: PromptSlot, val name: String, val chars: Int, val content: String)

    /** @property afterTurn 이 턴 **초과**만 원문에 넣었다 */
    data class RawWindowView(val recordedThroughTurn: Int, val afterTurn: Int, val messageCount: Int)

    data class Message(val role: MessageRole, val content: String)

    companion object {
        fun of(p: AssembledPrompt) = PromptPreviewResponse(
            storyId = p.storyId,
            totalChars = p.totalChars,
            systemChars = p.systemChars,
            messageChars = p.messageChars,
            sections = p.sections.map { Section(it.slot, it.name, it.chars, it.content) },
            activeCharacters = p.activeCharacters,
            activeKeywords = p.activeKeywords,
            rawWindow = RawWindowView(p.rawWindow.recordedThroughTurn, p.rawWindow.afterTurn, p.rawMessageCount),
            systemPrompt = p.systemPrompt,
            messages = p.messages.map { Message(it.role, it.content) },
        )
    }
}
