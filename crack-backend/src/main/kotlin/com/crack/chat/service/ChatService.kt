package com.crack.chat.service

import com.crack.ai.dto.AiPurpose
import com.crack.ai.dto.AiRequest
import com.crack.ai.service.AiGateway
import com.crack.chat.dto.ChatRequest
import com.crack.chat.dto.ParsedResponse
import com.crack.global.config.DataPaths
import com.crack.global.exception.NotFoundException
import com.crack.memory.service.MemoryService
import com.crack.prompt.service.PromptAssembler
import com.crack.scenario.repository.ScenarioRepository
import com.crack.story.entity.Story
import com.crack.story.repository.StoryRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.nio.file.Path
import java.time.LocalDateTime

@Service
class ChatService(
    private val aiGateway: AiGateway,
    private val promptAssembler: PromptAssembler,
    private val chatFileService: ChatFileService,
    private val messageParser: MessageParser,
    private val scenarioRepository: ScenarioRepository,
    private val storyRepository: StoryRepository,
    private val memoryService: MemoryService,
    private val dataPaths: DataPaths
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun streamChat(storyId: Long, request: ChatRequest): SseEmitter {
        val story = findStory(storyId)
        val scenario = scenarioRepository.findById(story.scenarioId)
            .orElseThrow { NotFoundException("시나리오를 찾을 수 없습니다.") }

        val storyPath = storyPath(story)
        val scenarioPath = dataPaths.scenarioDir(scenario.name)

        // 1. 사용자 메시지를 chat_latest.md에 기록
        chatFileService.appendUserMessage(storyPath, request.message)

        // 2. 시스템 프롬프트 조립
        val systemPrompt = promptAssembler.assembleSystemPrompt(scenarioPath, storyPath, request.activeCharacters)

        // 3. 대화 컨텍스트 로드
        val conversationMessages = promptAssembler.loadConversationContext(storyPath)

        // 4. AI 요청 구성
        val aiRequest = AiRequest(
            systemPrompt = systemPrompt,
            messages = conversationMessages,
            purpose = AiPurpose.CHAT
        )

        // 5. SSE 스트리밍 (프로바이더 선택)
        val emitter = SseEmitter(300_000L)
        aiGateway.stream(aiRequest, SseStreamListener(emitter), request.provider)
        return emitter
    }

    @Transactional
    fun onResponseComplete(storyId: Long, fullResponse: String) {
        if (fullResponse.isBlank()) {
            log.warn("빈 AI 응답 — 저장하지 않음: storyId=$storyId")
            return
        }

        val story = findStory(storyId)
        val storyPath = storyPath(story)

        chatFileService.appendAssistantMessage(storyPath, fullResponse)
        story.turnCount += 1
        story.updatedAt = LocalDateTime.now()
        storyRepository.save(story)

        val newTurnCount = story.turnCount

        // 10턴 도달 시 자동 요약
        if (memoryService.shouldSummarize(newTurnCount)) {
            log.info("${newTurnCount}턴 도달 — 자동 요약 시작: storyId=$storyId")
            try {
                val result = memoryService.summarize(storyId)
                log.info("자동 요약 완료: ${result.turnRange}턴, 갱신 캐릭터: ${result.charactersUpdated}")
            } catch (e: Exception) {
                log.error("자동 요약 실패: storyId=$storyId", e)
            }
        }
    }

    fun parseResponse(rawResponse: String): ParsedResponse {
        return messageParser.parse(rawResponse)
    }

    fun getChatHistory(storyId: Long): String {
        val story = findStory(storyId)
        return chatFileService.readChatLatest(storyPath(story))
    }

    fun editMessage(storyId: Long, messageIndex: Int, newContent: String): List<ChatFileService.ChatMessage> {
        val story = findStory(storyId)
        return chatFileService.editMessage(storyPath(story), messageIndex, newContent)
    }

    @Transactional
    fun deleteMessagesFrom(storyId: Long, messageIndex: Int): List<ChatFileService.ChatMessage> {
        val story = findStory(storyId)
        val remaining = chatFileService.deleteMessagesFrom(storyPath(story), messageIndex)
        // Recalculate turn count (number of assistant messages)
        val newTurnCount = remaining.count { it.role == "assistant" }
        story.turnCount = newTurnCount
        story.updatedAt = LocalDateTime.now()
        storyRepository.save(story)
        return remaining
    }

    @Transactional
    fun regenerate(storyId: Long, request: ChatRequest): SseEmitter {
        val story = findStory(storyId)
        val storyPath = storyPath(story)

        // Remove last assistant message
        chatFileService.removeLastAssistantMessage(storyPath)
        story.turnCount = maxOf(0, story.turnCount - 1)
        story.updatedAt = LocalDateTime.now()
        storyRepository.save(story)

        // Re-stream with the existing last user message
        val scenario = scenarioRepository.findById(story.scenarioId)
            .orElseThrow { NotFoundException("시나리오를 찾을 수 없습니다.") }
        val scenarioPath = dataPaths.scenarioDir(scenario.name)

        val systemPrompt = promptAssembler.assembleSystemPrompt(scenarioPath, storyPath, request.activeCharacters)
        val conversationMessages = promptAssembler.loadConversationContext(storyPath)

        val aiRequest = AiRequest(
            systemPrompt = systemPrompt,
            messages = conversationMessages,
            purpose = AiPurpose.CHAT
        )

        val emitter = SseEmitter(300_000L)
        aiGateway.stream(aiRequest, SseStreamListener(emitter), request.provider)
        return emitter
    }

    fun continueChat(storyId: Long, request: ChatRequest): SseEmitter {
        val story = findStory(storyId)
        val scenario = scenarioRepository.findById(story.scenarioId)
            .orElseThrow { NotFoundException("시나리오를 찾을 수 없습니다.") }

        val storyPath = storyPath(story)
        val scenarioPath = dataPaths.scenarioDir(scenario.name)

        // Append a system-like user message to prompt continuation
        chatFileService.appendUserMessage(storyPath, "계속 이어서 작성해주세요.")

        val systemPrompt = promptAssembler.assembleSystemPrompt(scenarioPath, storyPath, request.activeCharacters)
        val conversationMessages = promptAssembler.loadConversationContext(storyPath)

        val aiRequest = AiRequest(
            systemPrompt = systemPrompt,
            messages = conversationMessages,
            purpose = AiPurpose.CHAT
        )

        val emitter = SseEmitter(300_000L)
        aiGateway.stream(aiRequest, SseStreamListener(emitter), request.provider)
        return emitter
    }

    private fun storyPath(story: Story): Path {
        val scenario = scenarioRepository.findById(story.scenarioId)
            .orElseThrow { NotFoundException("시나리오를 찾을 수 없습니다.") }
        return dataPaths.storyDir(scenario.name, story.dirName)
    }

    private fun findStory(storyId: Long): Story =
        storyRepository.findById(storyId)
            .orElseThrow { NotFoundException("스토리를 찾을 수 없습니다: $storyId") }
}
