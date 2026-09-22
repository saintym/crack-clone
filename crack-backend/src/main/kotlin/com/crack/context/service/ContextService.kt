package com.crack.context.service

import com.crack.ai.dto.AiPurpose
import com.crack.ai.dto.AiRequest
import com.crack.ai.dto.ChatMessage
import com.crack.ai.dto.MessageRole
import com.crack.ai.service.AiGateway
import com.crack.context.dto.*
import com.crack.state.entity.EventType
import com.crack.state.entity.StateType
import com.crack.state.repository.CharacterEventRepository
import com.crack.state.repository.CharacterStateRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

@Service
class ContextService(
    private val aiGateway: AiGateway,
    private val characterStateRepository: CharacterStateRepository,
    private val characterEventRepository: CharacterEventRepository
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun analyzeContext(userMessage: String, recentHistory: String): ContextAnalysis {
        val prompt = buildAnalysisPrompt(userMessage, recentHistory)

        return try {
            val response = aiGateway.chat(AiRequest(
                systemPrompt = ANALYSIS_SYSTEM_PROMPT,
                messages = listOf(ChatMessage(MessageRole.USER, prompt)),
                maxTokens = 300,
                purpose = AiPurpose.UTILITY
            ))
            parseAnalysisResponse(response)
        } catch (e: Exception) {
            log.warn("Context analysis failed, using defaults: ${e.message}")
            defaultAnalysis()
        }
    }

    fun loadContext(storyId: Long, analysis: ContextAnalysis, currentTurn: Int): AssembledContext {
        val characterContexts = analysis.relevantCharacters.map { charName ->
            loadCharacterContext(storyId, charName, analysis, currentTurn)
        }

        val contextPrompt = buildContextPrompt(characterContexts)
        return AssembledContext(characterContexts, contextPrompt)
    }

    fun loadCharacterContext(
        storyId: Long,
        characterName: String,
        analysis: ContextAnalysis,
        currentTurn: Int
    ): CharacterContext {
        val states = analysis.neededStateTypes.associateWith { stateType ->
            characterStateRepository.findByStoryIdAndCharacterNameAndIsActiveTrue(storyId, characterName)
                .filter { it.stateType == stateType }
                .map { StateEntry(it.stateKey, it.stateValue, it.context) }
        }.filterValues { it.isNotEmpty() }

        val fromTurn = maxOf(1, currentTurn - analysis.recentTurnWindow)
        val recentEvents = if (analysis.neededEventTypes.isNotEmpty()) {
            characterEventRepository.findByStoryIdAndCharacterNameAndEventTypeInOrderByTurnNumberDesc(
                storyId, characterName, analysis.neededEventTypes
            ).filter { it.turnNumber >= fromTurn }
                .map { EventEntry(it.turnNumber, it.eventType, it.summary) }
        } else {
            emptyList()
        }

        return CharacterContext(characterName, states, recentEvents)
    }

    fun buildContextPrompt(characterContexts: List<CharacterContext>): String {
        if (characterContexts.isEmpty() || characterContexts.all { it.states.isEmpty() && it.recentEvents.isEmpty() }) {
            return ""
        }

        val sb = StringBuilder()
        sb.appendLine("## 캐릭터 상태 정보")
        sb.appendLine()

        for (cc in characterContexts) {
            if (cc.states.isEmpty() && cc.recentEvents.isEmpty()) continue

            sb.appendLine("### ${cc.characterName}")

            for ((stateType, entries) in cc.states) {
                sb.appendLine("**${stateType.toKorean()}:**")
                for (entry in entries) {
                    sb.append("- ${entry.key}: ${entry.value}")
                    if (entry.context != null) sb.append(" (${entry.context})")
                    sb.appendLine()
                }
            }

            if (cc.recentEvents.isNotEmpty()) {
                sb.appendLine("**최근 이벤트:**")
                for (event in cc.recentEvents) {
                    sb.appendLine("- [턴${event.turnNumber}] ${event.eventType.toKorean()}: ${event.summary}")
                }
            }
            sb.appendLine()
        }

        return sb.toString().trimEnd()
    }

    private fun buildAnalysisPrompt(userMessage: String, recentHistory: String): String {
        return """
최근 대화:
$recentHistory

사용자 메시지: $userMessage

위 대화에서 관련된 캐릭터 이름들과 필요한 정보 유형을 판별해주세요.
응답 형식:
CHARACTERS: 캐릭터1, 캐릭터2
STATE_TYPES: INVENTORY, LOCATION, RELATIONSHIP
EVENT_TYPES: RELATIONSHIP_CHANGE, MAJOR_EVENT
TURN_WINDOW: 10
        """.trimIndent()
    }

    fun parseAnalysisResponse(response: String): ContextAnalysis {
        val characters = extractList(response, "CHARACTERS")
        val stateTypes = extractList(response, "STATE_TYPES").mapNotNull { parseStateType(it) }
        val eventTypes = extractList(response, "EVENT_TYPES").mapNotNull { parseEventType(it) }
        val turnWindow = extractInt(response, "TURN_WINDOW") ?: 10

        return if (characters.isEmpty()) {
            defaultAnalysis()
        } else {
            ContextAnalysis(characters, stateTypes, eventTypes, turnWindow)
        }
    }

    private fun extractList(response: String, key: String): List<String> {
        val line = response.lines().find { it.startsWith("$key:") } ?: return emptyList()
        return line.substringAfter(":").split(",").map { it.trim() }.filter { it.isNotBlank() }
    }

    private fun extractInt(response: String, key: String): Int? {
        val line = response.lines().find { it.startsWith("$key:") } ?: return null
        return line.substringAfter(":").trim().toIntOrNull()
    }

    private fun parseStateType(value: String): StateType? = try {
        StateType.valueOf(value)
    } catch (e: Exception) { null }

    private fun parseEventType(value: String): EventType? = try {
        EventType.valueOf(value)
    } catch (e: Exception) { null }

    private fun defaultAnalysis() = ContextAnalysis(
        relevantCharacters = emptyList(),
        neededStateTypes = listOf(StateType.LOCATION, StateType.INVENTORY),
        neededEventTypes = listOf(EventType.RELATIONSHIP_CHANGE, EventType.MAJOR_EVENT)
    )

    companion object {
        const val ANALYSIS_SYSTEM_PROMPT = """당신은 대화 컨텍스트 분석기입니다.
사용자의 메시지와 최근 대화를 보고, 응답 생성에 필요한 캐릭터와 정보 유형을 판별해주세요.
반드시 지정된 형식으로만 응답하세요."""
    }
}

private fun StateType.toKorean(): String = when (this) {
    StateType.INVENTORY -> "소지품"
    StateType.LOCATION -> "위치"
    StateType.SCHEDULE -> "일정"
    StateType.RELATIONSHIP -> "관계"
    StateType.SKILL -> "능력"
    StateType.STATUS -> "상태"
}

private fun EventType.toKorean(): String = when (this) {
    EventType.RELATIONSHIP_CHANGE -> "관계변화"
    EventType.KNOWLEDGE_GAIN -> "지식획득"
    EventType.PERSONALITY_SHIFT -> "성격변화"
    EventType.MAJOR_EVENT -> "주요사건"
}
