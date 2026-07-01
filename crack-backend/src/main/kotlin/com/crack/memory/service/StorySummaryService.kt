package com.crack.memory.service

import com.crack.ai.dto.AiRequest
import com.crack.ai.dto.ChatMessage
import com.crack.ai.dto.MessageRole
import com.crack.ai.dto.ModelTier
import com.crack.ai.service.ClaudeService
import com.crack.memory.entity.StorySummary
import com.crack.memory.entity.SummaryLevel
import com.crack.memory.repository.StorySummaryRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class StorySummaryService(
    private val storySummaryRepository: StorySummaryRepository,
    private val claudeService: ClaudeService
) {
    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        const val L1_TURN_SIZE = 10    // 10턴마다 L1 요약
        const val L2_BATCH_SIZE = 3    // L1 3개 → L2 1개 (30턴)
        const val L3_BATCH_SIZE = 3    // L2 3개 → L3 1개 (~100턴)

        private const val CONSOLIDATION_PROMPT = """아래는 여러 개의 스토리 요약입니다.
이 요약들을 하나의 통합 요약으로 합쳐주세요.

규칙:
1. 핵심 사건과 인과관계를 중심으로 통합
2. 반복되는 내용은 하나로 합치기
3. 캐릭터별 변화 흐름이 드러나도록
4. 한국어로 작성
5. 3~5문단으로 요약"""
    }

    @Transactional
    fun saveL1Summary(storyId: Long, scenarioId: Long, fromTurn: Int, toTurn: Int, content: String): StorySummary {
        val summary = StorySummary(
            scenarioId = scenarioId,
            storyId = storyId,
            level = SummaryLevel.L1,
            fromTurn = fromTurn,
            toTurn = toTurn,
            content = content
        )
        val saved = storySummaryRepository.save(summary)
        log.info("L1 요약 저장: storyId=$storyId, ${fromTurn}~${toTurn}턴")

        tryConsolidate(storyId, scenarioId)
        return saved
    }

    @Transactional
    fun tryConsolidate(storyId: Long, scenarioId: Long) {
        consolidateIfReady(storyId, scenarioId, SummaryLevel.L1, SummaryLevel.L2, L2_BATCH_SIZE)
        consolidateIfReady(storyId, scenarioId, SummaryLevel.L2, SummaryLevel.L3, L3_BATCH_SIZE)
    }

    fun consolidateIfReady(
        storyId: Long,
        scenarioId: Long,
        sourceLevel: SummaryLevel,
        targetLevel: SummaryLevel,
        batchSize: Int
    ) {
        val sourceSummaries = storySummaryRepository.findByStoryIdAndLevelOrderByFromTurnAsc(storyId, sourceLevel)
        val existingTargets = storySummaryRepository.findByStoryIdAndLevelOrderByFromTurnAsc(storyId, targetLevel)

        val alreadyConsolidatedTurn = existingTargets.maxOfOrNull { it.toTurn } ?: 0
        val unconsolidated = sourceSummaries.filter { it.fromTurn > alreadyConsolidatedTurn }

        if (unconsolidated.size < batchSize) return

        val batch = unconsolidated.take(batchSize)
        val fromTurn = batch.first().fromTurn
        val toTurn = batch.last().toTurn
        val combinedContent = batch.joinToString("\n\n---\n\n") {
            "## ${it.fromTurn}~${it.toTurn}턴\n${it.content}"
        }

        val consolidated = requestConsolidation(combinedContent)

        val targetSummary = StorySummary(
            scenarioId = scenarioId,
            storyId = storyId,
            level = targetLevel,
            fromTurn = fromTurn,
            toTurn = toTurn,
            content = consolidated
        )
        storySummaryRepository.save(targetSummary)
        log.info("${targetLevel} 요약 생성: storyId=$storyId, ${fromTurn}~${toTurn}턴 (${sourceLevel} ${batch.size}개 통합)")
    }

    fun getSummariesByLevel(storyId: Long, level: SummaryLevel): List<StorySummary> {
        return storySummaryRepository.findByStoryIdAndLevelOrderByFromTurnAsc(storyId, level)
    }

    fun getEffectiveSummaries(storyId: Long): List<StorySummary> {
        val l3 = storySummaryRepository.findByStoryIdAndLevelOrderByFromTurnAsc(storyId, SummaryLevel.L3)
        val l2 = storySummaryRepository.findByStoryIdAndLevelOrderByFromTurnAsc(storyId, SummaryLevel.L2)
        val l1 = storySummaryRepository.findByStoryIdAndLevelOrderByFromTurnAsc(storyId, SummaryLevel.L1)

        val coveredTurns = mutableSetOf<Int>()
        val result = mutableListOf<StorySummary>()

        for (summary in l3) {
            result.add(summary)
            (summary.fromTurn..summary.toTurn).forEach { coveredTurns.add(it) }
        }
        for (summary in l2) {
            if ((summary.fromTurn..summary.toTurn).none { it in coveredTurns }) {
                result.add(summary)
                (summary.fromTurn..summary.toTurn).forEach { coveredTurns.add(it) }
            }
        }
        for (summary in l1) {
            if ((summary.fromTurn..summary.toTurn).none { it in coveredTurns }) {
                result.add(summary)
                (summary.fromTurn..summary.toTurn).forEach { coveredTurns.add(it) }
            }
        }

        return result.sortedBy { it.fromTurn }
    }

    fun requestConsolidation(combinedContent: String): String {
        val request = AiRequest(
            systemPrompt = CONSOLIDATION_PROMPT,
            messages = listOf(ChatMessage(MessageRole.USER, combinedContent)),
            maxTokens = 1024,
            modelTier = ModelTier.SONNET
        )
        return claudeService.chat(request)
    }

}
