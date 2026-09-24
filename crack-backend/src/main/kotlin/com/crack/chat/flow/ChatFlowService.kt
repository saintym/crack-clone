package com.crack.chat.flow

import com.crack.ai.dto.AiPurpose
import com.crack.ai.dto.AiRequest
import com.crack.ai.service.AiGateway
import com.crack.command.CommandService
import com.crack.global.exception.BadRequestException
import com.crack.global.exception.NotFoundException
import com.crack.memory.record.MemoryRecordService
import com.crack.memory.record.MemoryStatusView
import com.crack.message.dto.MessageView
import com.crack.message.dto.ResponseTags
import com.crack.message.dto.TruncateResult
import com.crack.message.entity.MessageKind
import com.crack.message.entity.MessageRole
import com.crack.message.entity.StoryMessage
import com.crack.message.repository.StoryMessageRepository
import com.crack.message.service.MessageService
import com.crack.prompt.service.AssembledPrompt
import com.crack.prompt.service.PromptAssembler
import com.crack.story.entity.Story
import com.crack.story.repository.StoryRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.http.MediaType
import org.springframework.stereotype.Service
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter

/**
 * 채팅 흐름 (DESIGN.md §5.2). 메시지 저장소(T03)와 콜백 스트리밍(T01) 위에서 동작한다.
 *
 * - **서버가 저장한다.** 프로바이더의 완료 콜백에서 저장한 뒤 `done`을 보낸다. 클라이언트가 끊겨도 저장된다.
 * - **실패 시 원본 보존.** 재생성 후보는 성공했을 때만 추가한다. 전송이 실패하면 유저 메시지는 남는다.
 * - **스토리당 생성 1개.** 생성 중에는 새 생성뿐 아니라 후보 선택·수정·삭제도 409로 막는다
 *   (생성 도중 대화가 바뀌어 응답이 엉뚱한 자리에 저장되는 것을 막는다).
 * - 첫 줄 감정·인물 태그는 [EmotionTagFilter]가 스트림에서 떼어 내고 `emotion`·`speaker`·`speaker_variant` 칼럼에만 저장한다(§5.3).
 * - 프롬프트는 [PromptAssembler](v2, DESIGN.md §6)가 만든다. 이번 턴 지시는 BOTTOM 슬롯(`[지시]` 블록)으로 들어간다.
 * - 사용자 정의 `/` 명령(T16, DESIGN.md §8.2)은 유저 메시지를 `COMMAND`로 저장하고, 명령 프롬프트를 그 턴의 지시로만 넣는다.
 *   그 턴을 재생성할 때도 같은 지시를 다시 넣는다.
 * - 매 턴 응답 전에 LLM을 추가로 호출하지 않는다. 옛 10턴 자동 요약은 제거했다(기억은 T14가 [AfterTurnHook]으로 연결).
 */
@Service
class ChatFlowService(
    private val messageService: MessageService,
    private val messageRepository: StoryMessageRepository,
    private val storyRepository: StoryRepository,
    private val promptAssembler: PromptAssembler,
    private val aiGateway: AiGateway,
    private val generationLock: StoryGenerationLock,
    private val afterTurnHooks: ObjectProvider<AfterTurnHook>,
    private val memoryRecordService: MemoryRecordService,
    private val commandService: CommandService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    // ---- 조회 ----

    fun state(storyId: Long): StoryMessagesState {
        val messages = messageService.list(storyId) // 스토리가 없으면 404
        val story = requireStory(storyId)
        return StoryMessagesState(
            story = StoryChatInfo(
                turnCount = story.turnCount,
                recordedThroughTurn = story.recordedThroughTurn,
                generating = generationLock.isLocked(storyId),
                memory = memoryRecordService.status(storyId),
            ),
            messages = messages,
        )
    }

    // ---- 생성 (SSE) ----

    /**
     * 유저 메시지를 저장하고 `user` 이벤트를 보낸 뒤 응답을 생성한다.
     *
     * @param command 사용자 정의 `/` 명령 이름(앞의 `/`는 있어도 된다). 주면 유저 메시지를 `kind = COMMAND`로 저장하고
     *   명령 프롬프트와 인자를 이번 턴 지시로 넣는다. 모르는 명령이나 시스템 명령이면 400이고 아무것도 저장하지 않는다.
     */
    fun send(storyId: Long, content: String?, provider: String?, command: String? = null): SseEmitter {
        if (content.isNullOrBlank()) throw BadRequestException("메시지 내용이 비어 있습니다")
        val story = requireStory(storyId)
        val custom = command?.takeIf { it.isNotBlank() }?.let { commandService.requireCustom(storyId, it) }
        val turnInstruction = custom?.let { CommandService.turnInstruction(it, content) }
        val kind = if (custom != null) MessageKind.COMMAND else MessageKind.NORMAL
        return withGeneration(storyId) { ticket ->
            val user = messageService.appendUser(storyId, content, kind)
            val emitter = newEmitter()
            sendEarly(emitter, SseEvents.USER, messageService.view(user))
            launch(story, ticket, emitter, provider, GenerationMode.SEND,
                { promptAssembler.assemble(storyId, turnInstruction = turnInstruction) }) { body, tags ->
                messageService.appendAssistant(storyId, body, tags, MessageKind.NORMAL, turnNo = user.turnNo)
            }
            emitter
        }
    }

    /**
     * 마지막 ASSISTANT에 후보를 추가한다. 마지막 메시지가 USER면(전송 실패 등) 그 턴의 첫 응답을 만든다.
     *
     * @param messageId 클라이언트가 재생성하려는 메시지. 주면 **대화의 마지막 메시지**여야 한다(아니면 400, D17).
     * @param instruction 재생성 지시. 이번 요청에만 넣고 후보의 `instruction`에 기록한다.
     */
    fun regenerate(storyId: Long, provider: String?, instruction: String?, messageId: Long?): SseEmitter {
        val story = requireStory(storyId)
        if (messageId != null) requireMessageInStory(storyId, messageId)
        val trimmedInstruction = instruction?.trim()?.takeIf { it.isNotEmpty() }
        return withGeneration(storyId) { ticket ->
            val last = messageRepository.findFirstByStoryIdOrderBySeqDesc(storyId)
                ?: throw BadRequestException("재생성할 메시지가 없습니다")
            if (messageId != null && messageId != last.id) {
                throw BadRequestException("가장 최근 응답만 재생성할 수 있습니다: $messageId")
            }
            val emitter = newEmitter()
            if (last.role == MessageRole.USER) {
                val turnInstruction = ConversationBuilder.combine(commandInstructionFor(storyId, last), trimmedInstruction)
                launch(story, ticket, emitter, provider, GenerationMode.REGENERATE,
                    { promptAssembler.assemble(storyId, turnInstruction = turnInstruction) }) { body, tags ->
                    messageService.appendAssistant(storyId, body, tags, MessageKind.NORMAL, turnNo = last.turnNo)
                }
            } else {
                if (last.kind == MessageKind.PROLOGUE) throw BadRequestException("프롤로그는 재생성할 수 없습니다")
                val turnInstruction = if (last.kind == MessageKind.CONTINUATION) {
                    ConversationBuilder.combine(ConversationBuilder.CONTINUE_INSTRUCTION, trimmedInstruction)
                } else {
                    val user = messageRepository.findByStoryIdAndTurnNoBetweenOrderBySeqAsc(storyId, last.turnNo, last.turnNo)
                        .firstOrNull { it.role == MessageRole.USER && it.seq < last.seq }
                    ConversationBuilder.combine(user?.let { commandInstructionFor(storyId, it) }, trimmedInstruction)
                }
                launch(story, ticket, emitter, provider, GenerationMode.REGENERATE,
                    { promptAssembler.assemble(storyId, beforeSeq = last.seq, turnInstruction = turnInstruction) }) { body, tags ->
                    messageService.addVariant(last.id, body, tags, trimmedInstruction)
                }
            }
            emitter
        }
    }

    /** 가상 지시로 이어쓰기한다. 지시문은 저장하지 않는다. 결과는 `kind = CONTINUATION` 새 턴. */
    fun continueStory(storyId: Long, provider: String?): SseEmitter {
        val story = requireStory(storyId)
        return withGeneration(storyId) { ticket ->
            val last = messageRepository.findFirstByStoryIdOrderBySeqDesc(storyId)
                ?: throw BadRequestException("이어 쓸 대화가 없습니다")
            if (last.role == MessageRole.USER) {
                throw BadRequestException("마지막 유저 메시지에 대한 응답이 없습니다. 재생성으로 응답을 먼저 만드세요")
            }
            val emitter = newEmitter()
            launch(story, ticket, emitter, provider, GenerationMode.CONTINUE,
                { promptAssembler.assemble(storyId, turnInstruction = ConversationBuilder.CONTINUE_INSTRUCTION) }) { body, tags ->
                messageService.appendAssistant(storyId, body, tags, MessageKind.CONTINUATION)
            }
            emitter
        }
    }

    // ---- 수정 ----

    fun selectVariant(storyId: Long, messageId: Long, index: Int): MessageView =
        withMutation(storyId) {
            requireMessageInStory(storyId, messageId)
            messageService.view(messageService.selectVariant(messageId, index))
        }

    fun edit(storyId: Long, messageId: Long, content: String?): MessageView {
        if (content.isNullOrBlank()) throw BadRequestException("메시지 내용이 비어 있습니다")
        return withMutation(storyId) {
            requireMessageInStory(storyId, messageId)
            messageService.view(messageService.edit(messageId, content))
        }
    }

    fun truncateFrom(storyId: Long, messageId: Long): TruncateResult =
        withMutation(storyId) {
            requireMessageInStory(storyId, messageId)
            messageService.truncateFrom(messageId)
        }

    // ---- 내부 ----

    /**
     * 락을 잡고 [block]을 실행한다. [block]이 예외를 던지면 락을 풀고 다시 던진다.
     * 정상 반환하면 락은 [launch]가 넘긴 리스너가 종료 시 푼다.
     */
    private fun <T> withGeneration(storyId: Long, block: (StoryGenerationLock.Ticket) -> T): T {
        val ticket = generationLock.acquire(storyId)
        try {
            return block(ticket)
        } catch (e: Throwable) {
            ticket.release()
            throw e
        }
    }

    private fun <T> withMutation(storyId: Long, block: () -> T): T {
        val ticket = generationLock.acquire(storyId)
        try {
            return block()
        } finally {
            ticket.release()
        }
    }

    /**
     * 프롬프트를 만들고 스트리밍을 시작한다. **예외를 던지지 않는다.** 준비 중 실패도 SSE `error`로 보내고 락을 푼다.
     */
    private fun launch(
        story: Story,
        ticket: StoryGenerationLock.Ticket,
        emitter: SseEmitter,
        provider: String?,
        mode: GenerationMode,
        prompt: () -> AssembledPrompt,
        save: (body: String, tags: ResponseTags) -> StoryMessage,
    ) {
        val listener = GenerationStreamListener(
            storyId = story.id,
            emitter = emitter,
            ticket = ticket,
            save = { body, tags -> messageService.view(save(body, tags)) },
            afterSave = { saved -> runAfterTurnHooks(story.id, saved, mode) },
        )
        val filter = EmotionTagFilter(listener)
        try {
            val assembled = prompt()
            val request = AiRequest(
                systemPrompt = assembled.systemPrompt,
                messages = assembled.messages,
                purpose = AiPurpose.CHAT,
            )
            aiGateway.stream(request, filter, provider)
        } catch (e: Exception) {
            log.error("응답 생성 준비 실패: storyId=${story.id}", e)
            listener.onError(e)
        }
    }

    /**
     * 재생성용: 저장된 유저 메시지가 `COMMAND`면 그 명령의 이번 턴 지시를 다시 만든다(DESIGN.md §8.2).
     * 명령을 찾지 못하면 지시 없이 생성한다(재생성 자체는 막지 않는다).
     */
    private fun commandInstructionFor(storyId: Long, user: StoryMessage): String? {
        if (user.kind != MessageKind.COMMAND) return null
        return try {
            commandService.instructionForStored(storyId, user.content).also {
                if (it == null) log.warn("재생성: 명령을 찾지 못해 지시 없이 생성합니다: storyId={}, messageId={}", storyId, user.id)
            }
        } catch (e: Exception) {
            log.warn("재생성: 명령 지시를 만들지 못했습니다: storyId={}, messageId={}", storyId, user.id, e)
            null
        }
    }

    private fun runAfterTurnHooks(storyId: Long, saved: MessageView, mode: GenerationMode) {
        val hooks = afterTurnHooks.orderedStream().toList()
        if (hooks.isEmpty()) return
        val turnCount = storyRepository.findById(storyId).map { it.turnCount }.orElse(saved.turn)
        val event = AfterTurnEvent(storyId, saved.id, saved.turn, turnCount, mode)
        for (hook in hooks) {
            try {
                hook.afterTurn(event)
            } catch (e: Exception) {
                log.error("AfterTurnHook 실패: ${hook.javaClass.simpleName}, $event", e)
            }
        }
    }

    /** 핸들러가 emitter를 반환하기 전에 보내는 이벤트. SseEmitter가 버퍼링했다가 연결되면 보낸다. */
    private fun sendEarly(emitter: SseEmitter, name: String, data: Any) {
        emitter.send(SseEmitter.event().name(name).data(data, MediaType.APPLICATION_JSON))
    }

    private fun newEmitter() = SseEmitter(SSE_TIMEOUT_MS)

    private fun requireStory(storyId: Long): Story =
        storyRepository.findById(storyId).orElseThrow { NotFoundException("스토리를 찾을 수 없습니다: $storyId") }

    private fun requireMessageInStory(storyId: Long, messageId: Long): StoryMessage =
        messageRepository.findByIdAndStoryId(messageId, storyId)
            ?: throw NotFoundException("메시지를 찾을 수 없습니다: $messageId")

    companion object {
        /** SSE 연결 유지 시간. CLI 타임아웃 기본값(300초)보다 넉넉하게 둔다. 지나도 생성·저장은 계속된다. */
        const val SSE_TIMEOUT_MS = 15 * 60 * 1000L
    }
}

/** `GET /messages` 응답 (DESIGN.md §5.2) */
data class StoryMessagesState(
    val story: StoryChatInfo,
    val messages: List<MessageView>,
)

data class StoryChatInfo(
    val turnCount: Int,
    /** 기억 기록이 반영된 마지막 턴 (`stories.recorded_through_turn`) */
    val recordedThroughTurn: Int,
    val generating: Boolean,
    /** 기억 기록 뱃지 상태 (T14, DESIGN.md §7.2) */
    val memory: MemoryStatusView = MemoryStatusView.NONE,
)
