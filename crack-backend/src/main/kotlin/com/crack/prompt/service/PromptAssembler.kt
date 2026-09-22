package com.crack.prompt.service

import com.crack.ai.dto.ChatMessage
import com.crack.chat.flow.ConversationBuilder
import com.crack.chat.flow.RawWindow
import com.crack.message.dto.MessageView
import com.crack.message.entity.MessageRole
import com.crack.message.service.MessageService
import com.crack.prompt.config.PromptProperties
import com.crack.prompt.contributor.ActiveCharacterSelector
import com.crack.prompt.contributor.PromptContext
import com.crack.prompt.contributor.PromptContributor
import com.crack.prompt.contributor.PromptSlot
import com.crack.prompt.keyword.KeywordBook
import com.crack.story.files.StoryDirs
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/** 기여자 하나가 만든 섹션. */
data class PromptSection(val slot: PromptSlot, val name: String, val content: String) {
    val chars: Int get() = content.length
}

/**
 * 조립 결과 (DESIGN.md §6).
 *
 * @property systemPrompt BASE…IMAGES 섹션을 빈 줄로 이은 것
 * @property messages 대화 원문(§6.1) + 맨 끝 유저 메시지. BOTTOM 섹션이 `[지시]` 블록으로 붙은 최종 형태
 * @property sections 조립 순서대로 모든 섹션(BOTTOM 포함)
 * @property activeCharacters CHARACTERS 슬롯에 들어간 인물(§6.2)
 * @property rawMessageCount 원문 범위에 든 저장 메시지 수(가상 입력 제외)
 * @property activeKeywords KEYWORDS 슬롯에 들어간 키워드북 항목 제목(§8.3, 우선순위 순)
 */
data class AssembledPrompt(
    val storyId: Long,
    val systemPrompt: String,
    val messages: List<ChatMessage>,
    val sections: List<PromptSection>,
    val activeCharacters: List<String>,
    val rawWindow: RawWindow,
    val rawMessageCount: Int,
    val activeKeywords: List<String> = emptyList(),
) {
    val systemChars: Int get() = systemPrompt.length
    val messageChars: Int get() = messages.sumOf { it.content.length }
    val totalChars: Int get() = systemChars + messageChars
}

/**
 * 매 턴 프롬프트 조립기 v2 (DESIGN.md §6, T13). **LLM을 부르지 않고 스토리 폴더와 메시지 저장소만 읽는다**(D8, D12).
 *
 * 1. 대화(재생성 대상 앞까지)에서 이번 입력과 `recentText`(최근 N개 메시지 + 이번 입력)를 정한다.
 * 2. [PromptContributor] 빈을 `(slot, order)` 순으로 불러 섹션을 모은다. 예외를 던진 기여자는 건너뛴다.
 * 3. `system` = BASE…IMAGES. `messages` = [ConversationBuilder]의 원문 범위 + BOTTOM 섹션을 이은 `[지시]` 블록.
 * 4. 섹션별 글자 수를 INFO 로그로 남긴다(§6.3).
 */
@Service
class PromptAssembler(
    contributors: List<PromptContributor>,
    private val storyDirs: StoryDirs,
    private val messageService: MessageService,
    private val conversationBuilder: ConversationBuilder,
    private val characterSelector: ActiveCharacterSelector,
    private val properties: PromptProperties,
    /** 발동 키워드 보고용(T17). 섹션 자체는 [com.crack.prompt.keyword.KeywordBookContributor]가 만든다 */
    private val keywordBook: KeywordBook? = null,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    private val contributors: List<PromptContributor> =
        contributors.sortedWith(compareBy<PromptContributor>({ it.slot.ordinal }, { it.order }, { it.name }))

    /**
     * @param beforeSeq 이 seq **미만**의 메시지만 쓴다(재생성 대상 앞까지). null이면 전부
     * @param turnInstruction 이번 턴 지시(이어쓰기, 재생성 지시, `/` 명령). 저장하지 않는다
     * @param pendingInput 저장하지 않은 가상 유저 입력(preview). 주면 이것이 이번 입력이다
     */
    fun assemble(
        storyId: Long,
        beforeSeq: Int? = null,
        turnInstruction: String? = null,
        pendingInput: String? = null,
    ): AssembledPrompt {
        val storyDir = storyDirs.locate(storyId).dir
        val views = messageService.list(storyId).filter { beforeSeq == null || it.seq < beforeSeq }
        val pending = pendingInput?.takeIf { it.isNotBlank() }

        val ctx = PromptContext(
            storyId = storyId,
            storyDir = storyDir,
            recentText = recentText(views, pending),
            userInput = pending ?: views.lastOrNull()?.takeIf { it.role == MessageRole.USER }?.content,
            turnInstruction = turnInstruction?.trim()?.takeIf { it.isNotEmpty() },
        )
        val sections = buildSections(ctx)
        val systemPrompt = sections.filter { it.slot.inSystemPrompt }.joinToString("\n\n") { it.content }
        val bottom = sections.filter { !it.slot.inSystemPrompt }.joinToString("\n\n") { it.content }.ifEmpty { null }

        val window = conversationBuilder.rawWindow(storyId, views)
        val raw = views.filter { window.includes(it.turn) }
        val result = AssembledPrompt(
            storyId = storyId,
            systemPrompt = systemPrompt,
            messages = conversationBuilder.build(raw, bottom, pending),
            sections = sections,
            activeCharacters = characterSelector.select(ctx).map { it.name },
            rawWindow = window,
            rawMessageCount = raw.size,
            activeKeywords = keywordBook?.select(ctx.storyDir, ctx.recentText)?.map { it.id }.orEmpty(),
        )
        logSizes(result)
        return result
    }

    /** 기여자를 순서대로 불러 섹션을 모은다. DB 없이 [ctx]만으로 동작한다(측정·테스트용으로도 쓴다). */
    fun buildSections(ctx: PromptContext): List<PromptSection> =
        contributors.mapNotNull { contributor ->
            val content = try {
                contributor.contribute(ctx)
            } catch (e: Exception) {
                log.error("프롬프트 기여자 실패로 섹션을 뺍니다: {} (storyId={})", contributor.name, ctx.storyId, e)
                null
            }
            content?.takeIf { it.isNotBlank() }?.let { PromptSection(contributor.slot, contributor.name, it.trimEnd()) }
        }

    /** 이번 입력을 뺀 대화의 최근 N개 메시지 + 이번 입력 (DESIGN.md §6). */
    private fun recentText(views: List<MessageView>, pending: String?): String {
        val history = if (pending == null && views.lastOrNull()?.role == MessageRole.USER) views.dropLast(1) else views
        val input = pending ?: views.lastOrNull()?.takeIf { it.role == MessageRole.USER }?.content
        val n = properties.keywordScanMessages.coerceAtLeast(0)
        return (history.takeLast(n).map { it.content } + listOfNotNull(input)).joinToString("\n")
    }

    private fun logSizes(p: AssembledPrompt) {
        if (!log.isInfoEnabled) return
        val sections = p.sections.joinToString(", ") { "${it.slot}/${it.name}=${it.chars}" }
        log.info(
            "프롬프트 조립 storyId={} total={} system={} messages={}개/{}자 raw(after={}, recorded={}, {}개) characters={} keywords={} sections=[{}]",
            p.storyId, p.totalChars, p.systemChars, p.messages.size, p.messageChars,
            p.rawWindow.afterTurn, p.rawWindow.recordedThroughTurn, p.rawMessageCount, p.activeCharacters, p.activeKeywords, sections,
        )
    }
}
