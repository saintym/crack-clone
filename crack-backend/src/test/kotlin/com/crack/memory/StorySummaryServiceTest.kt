package com.crack.memory

import com.crack.ai.service.AiGateway
import com.crack.memory.entity.StorySummary
import com.crack.memory.entity.SummaryLevel
import com.crack.memory.repository.StorySummaryRepository
import com.crack.memory.service.StorySummaryService
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.*

@ExtendWith(MockitoExtension::class)
class StorySummaryServiceTest {

    private lateinit var storySummaryRepository: StorySummaryRepository
    private lateinit var aiGateway: AiGateway
    private lateinit var storySummaryService: StorySummaryService

    private val testStoryId = 1L
    private val testScenarioId = 1L

    @BeforeEach
    fun setUp() {
        storySummaryRepository = mock()
        aiGateway = mock()
        storySummaryService = StorySummaryService(storySummaryRepository, aiGateway)
    }

    @Test
    fun `L1 요약을 저장하면 DB에 기록된다`() {
        // given
        whenever(storySummaryRepository.save(any<StorySummary>())).thenAnswer { it.getArgument(0) }
        whenever(storySummaryRepository.findByStoryIdAndLevelOrderByFromTurnAsc(eq(testStoryId), any()))
            .thenReturn(emptyList())

        // when
        val result = storySummaryService.saveL1Summary(testStoryId, testScenarioId, 1, 10, "첫 번째 요약")

        // then
        assertEquals(SummaryLevel.L1, result.level)
        assertEquals(1, result.fromTurn)
        assertEquals(10, result.toTurn)
        assertEquals("첫 번째 요약", result.content)
        verify(storySummaryRepository).save(any<StorySummary>())
    }

    @Test
    fun `L1 3개 누적 시 L2 통합 요약이 생성된다`() {
        // given
        val l1Summaries = listOf(
            StorySummary(id = 1, scenarioId = testScenarioId, storyId = testStoryId, level = SummaryLevel.L1, fromTurn = 1, toTurn = 10, content = "요약1"),
            StorySummary(id = 2, scenarioId = testScenarioId, storyId = testStoryId, level = SummaryLevel.L1, fromTurn = 11, toTurn = 20, content = "요약2"),
            StorySummary(id = 3, scenarioId = testScenarioId, storyId = testStoryId, level = SummaryLevel.L1, fromTurn = 21, toTurn = 30, content = "요약3")
        )
        whenever(storySummaryRepository.findByStoryIdAndLevelOrderByFromTurnAsc(testStoryId, SummaryLevel.L1))
            .thenReturn(l1Summaries)
        whenever(storySummaryRepository.findByStoryIdAndLevelOrderByFromTurnAsc(testStoryId, SummaryLevel.L2))
            .thenReturn(emptyList())
        whenever(aiGateway.chat(any(), anyOrNull())).thenReturn("통합된 L2 요약")
        whenever(storySummaryRepository.save(any<StorySummary>())).thenAnswer { it.getArgument(0) }

        // when
        storySummaryService.consolidateIfReady(testStoryId, testScenarioId, SummaryLevel.L1, SummaryLevel.L2, 3)

        // then
        verify(storySummaryRepository).save(argThat<StorySummary> {
            level == SummaryLevel.L2 && fromTurn == 1 && toTurn == 30
        })
        verify(aiGateway).chat(any(), anyOrNull())
    }

    @Test
    fun `L1이 2개뿐이면 L2 통합이 발생하지 않는다`() {
        // given
        val l1Summaries = listOf(
            StorySummary(id = 1, scenarioId = testScenarioId, storyId = testStoryId, level = SummaryLevel.L1, fromTurn = 1, toTurn = 10, content = "요약1"),
            StorySummary(id = 2, scenarioId = testScenarioId, storyId = testStoryId, level = SummaryLevel.L1, fromTurn = 11, toTurn = 20, content = "요약2")
        )
        whenever(storySummaryRepository.findByStoryIdAndLevelOrderByFromTurnAsc(testStoryId, SummaryLevel.L1))
            .thenReturn(l1Summaries)
        whenever(storySummaryRepository.findByStoryIdAndLevelOrderByFromTurnAsc(testStoryId, SummaryLevel.L2))
            .thenReturn(emptyList())

        // when
        storySummaryService.consolidateIfReady(testStoryId, testScenarioId, SummaryLevel.L1, SummaryLevel.L2, 3)

        // then
        verify(storySummaryRepository, never()).save(any<StorySummary>())
        verify(aiGateway, never()).chat(any(), anyOrNull())
    }

    @Test
    fun `이미 통합된 L1은 다시 통합하지 않는다`() {
        // given
        val l1Summaries = listOf(
            StorySummary(id = 1, scenarioId = testScenarioId, storyId = testStoryId, level = SummaryLevel.L1, fromTurn = 1, toTurn = 10, content = "요약1"),
            StorySummary(id = 2, scenarioId = testScenarioId, storyId = testStoryId, level = SummaryLevel.L1, fromTurn = 11, toTurn = 20, content = "요약2"),
            StorySummary(id = 3, scenarioId = testScenarioId, storyId = testStoryId, level = SummaryLevel.L1, fromTurn = 21, toTurn = 30, content = "요약3"),
            StorySummary(id = 4, scenarioId = testScenarioId, storyId = testStoryId, level = SummaryLevel.L1, fromTurn = 31, toTurn = 40, content = "요약4")
        )
        val existingL2 = listOf(
            StorySummary(id = 10, scenarioId = testScenarioId, storyId = testStoryId, level = SummaryLevel.L2, fromTurn = 1, toTurn = 30, content = "L2 요약")
        )
        whenever(storySummaryRepository.findByStoryIdAndLevelOrderByFromTurnAsc(testStoryId, SummaryLevel.L1))
            .thenReturn(l1Summaries)
        whenever(storySummaryRepository.findByStoryIdAndLevelOrderByFromTurnAsc(testStoryId, SummaryLevel.L2))
            .thenReturn(existingL2)

        // when
        storySummaryService.consolidateIfReady(testStoryId, testScenarioId, SummaryLevel.L1, SummaryLevel.L2, 3)

        // then — L1 4개 중 3개는 이미 통합됨, 미통합 1개는 batchSize 미달
        verify(storySummaryRepository, never()).save(any<StorySummary>())
    }

    @Test
    fun `L2 3개 누적 시 L3 통합 요약이 생성된다`() {
        // given
        val l2Summaries = listOf(
            StorySummary(id = 10, scenarioId = testScenarioId, storyId = testStoryId, level = SummaryLevel.L2, fromTurn = 1, toTurn = 30, content = "L2-1"),
            StorySummary(id = 11, scenarioId = testScenarioId, storyId = testStoryId, level = SummaryLevel.L2, fromTurn = 31, toTurn = 60, content = "L2-2"),
            StorySummary(id = 12, scenarioId = testScenarioId, storyId = testStoryId, level = SummaryLevel.L2, fromTurn = 61, toTurn = 90, content = "L2-3")
        )
        whenever(storySummaryRepository.findByStoryIdAndLevelOrderByFromTurnAsc(testStoryId, SummaryLevel.L2))
            .thenReturn(l2Summaries)
        whenever(storySummaryRepository.findByStoryIdAndLevelOrderByFromTurnAsc(testStoryId, SummaryLevel.L3))
            .thenReturn(emptyList())
        whenever(aiGateway.chat(any(), anyOrNull())).thenReturn("통합된 L3 요약")
        whenever(storySummaryRepository.save(any<StorySummary>())).thenAnswer { it.getArgument(0) }

        // when
        storySummaryService.consolidateIfReady(testStoryId, testScenarioId, SummaryLevel.L2, SummaryLevel.L3, 3)

        // then
        verify(storySummaryRepository).save(argThat<StorySummary> {
            level == SummaryLevel.L3 && fromTurn == 1 && toTurn == 90
        })
    }

    @Test
    fun `effectiveSummaries는 상위 레벨 우선으로 중복 없이 반환한다`() {
        // given
        val l3 = listOf(
            StorySummary(id = 100, scenarioId = testScenarioId, storyId = testStoryId, level = SummaryLevel.L3, fromTurn = 1, toTurn = 90, content = "L3")
        )
        val l2 = listOf(
            StorySummary(id = 10, scenarioId = testScenarioId, storyId = testStoryId, level = SummaryLevel.L2, fromTurn = 1, toTurn = 30, content = "L2-1"),
            StorySummary(id = 11, scenarioId = testScenarioId, storyId = testStoryId, level = SummaryLevel.L2, fromTurn = 31, toTurn = 60, content = "L2-2"),
            StorySummary(id = 12, scenarioId = testScenarioId, storyId = testStoryId, level = SummaryLevel.L2, fromTurn = 61, toTurn = 90, content = "L2-3"),
            StorySummary(id = 13, scenarioId = testScenarioId, storyId = testStoryId, level = SummaryLevel.L2, fromTurn = 91, toTurn = 120, content = "L2-4")
        )
        val l1 = listOf(
            StorySummary(id = 1, scenarioId = testScenarioId, storyId = testStoryId, level = SummaryLevel.L1, fromTurn = 121, toTurn = 130, content = "L1-latest")
        )

        whenever(storySummaryRepository.findByStoryIdAndLevelOrderByFromTurnAsc(testStoryId, SummaryLevel.L3)).thenReturn(l3)
        whenever(storySummaryRepository.findByStoryIdAndLevelOrderByFromTurnAsc(testStoryId, SummaryLevel.L2)).thenReturn(l2)
        whenever(storySummaryRepository.findByStoryIdAndLevelOrderByFromTurnAsc(testStoryId, SummaryLevel.L1)).thenReturn(l1)

        // when
        val result = storySummaryService.getEffectiveSummaries(testStoryId)

        // then — L3(1~90) + L2(91~120) + L1(121~130) = 3개
        assertEquals(3, result.size)
        assertEquals(SummaryLevel.L3, result[0].level)
        assertEquals(1, result[0].fromTurn)
        assertEquals(SummaryLevel.L2, result[1].level)
        assertEquals(91, result[1].fromTurn)
        assertEquals(SummaryLevel.L1, result[2].level)
        assertEquals(121, result[2].fromTurn)
    }

    @Test
    fun `요약이 없으면 빈 리스트를 반환한다`() {
        // given
        whenever(storySummaryRepository.findByStoryIdAndLevelOrderByFromTurnAsc(eq(testStoryId), any()))
            .thenReturn(emptyList())

        // when
        val result = storySummaryService.getEffectiveSummaries(testStoryId)

        // then
        assertTrue(result.isEmpty())
    }

    @Test
    fun `레벨별 요약을 조회할 수 있다`() {
        // given
        val l1Summaries = listOf(
            StorySummary(id = 1, scenarioId = testScenarioId, storyId = testStoryId, level = SummaryLevel.L1, fromTurn = 1, toTurn = 10, content = "요약1"),
            StorySummary(id = 2, scenarioId = testScenarioId, storyId = testStoryId, level = SummaryLevel.L1, fromTurn = 11, toTurn = 20, content = "요약2")
        )
        whenever(storySummaryRepository.findByStoryIdAndLevelOrderByFromTurnAsc(testStoryId, SummaryLevel.L1))
            .thenReturn(l1Summaries)

        // when
        val result = storySummaryService.getSummariesByLevel(testStoryId, SummaryLevel.L1)

        // then
        assertEquals(2, result.size)
        assertEquals(1, result[0].fromTurn)
        assertEquals(11, result[1].fromTurn)
    }
}
