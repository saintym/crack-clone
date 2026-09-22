package com.crack.context

import com.crack.ai.service.AiGateway
import com.crack.context.dto.*
import com.crack.context.service.ContextService
import com.crack.state.entity.*
import com.crack.state.repository.CharacterEventRepository
import com.crack.state.repository.CharacterStateRepository
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.*

@ExtendWith(MockitoExtension::class)
class ContextServiceTest {

    private lateinit var aiGateway: AiGateway
    private lateinit var characterStateRepository: CharacterStateRepository
    private lateinit var characterEventRepository: CharacterEventRepository
    private lateinit var contextService: ContextService

    private val testStoryId = 1L

    @BeforeEach
    fun setUp() {
        aiGateway = mock()
        characterStateRepository = mock()
        characterEventRepository = mock()
        contextService = ContextService(
            aiGateway, characterStateRepository, characterEventRepository
        )
    }

    @Test
    fun `Haiku 응답을 올바르게 파싱한다`() {
        // given
        val response = """
CHARACTERS: 히로인, 동료
STATE_TYPES: INVENTORY, LOCATION
EVENT_TYPES: RELATIONSHIP_CHANGE, MAJOR_EVENT
TURN_WINDOW: 15
        """.trimIndent()

        // when
        val result = contextService.parseAnalysisResponse(response)

        // then
        assertEquals(listOf("히로인", "동료"), result.relevantCharacters)
        assertEquals(listOf(StateType.INVENTORY, StateType.LOCATION), result.neededStateTypes)
        assertEquals(listOf(EventType.RELATIONSHIP_CHANGE, EventType.MAJOR_EVENT), result.neededEventTypes)
        assertEquals(15, result.recentTurnWindow)
    }

    @Test
    fun `파싱 실패 시 기본 분석결과를 반환한다`() {
        // given
        val response = "잘못된 응답 형식입니다"

        // when
        val result = contextService.parseAnalysisResponse(response)

        // then
        assertTrue(result.relevantCharacters.isEmpty())
        assertEquals(listOf(StateType.LOCATION, StateType.INVENTORY), result.neededStateTypes)
    }

    @Test
    fun `캐릭터 컨텍스트를 올바르게 로드한다`() {
        // given
        val states = listOf(
            CharacterState(id = 1L, scenarioId = 1L, storyId = testStoryId, characterName = "히로인",
                stateType = StateType.INVENTORY, stateKey = "검", stateValue = "철검",
                context = "상점 구매"),
            CharacterState(id = 2L, scenarioId = 1L, storyId = testStoryId, characterName = "히로인",
                stateType = StateType.LOCATION, stateKey = "현재위치", stateValue = "마을 광장")
        )
        val events = listOf(
            CharacterEvent(id = 1L, scenarioId = 1L, storyId = testStoryId, characterName = "히로인",
                turnNumber = 8, eventType = EventType.RELATIONSHIP_CHANGE,
                summary = "호감도 상승")
        )

        whenever(characterStateRepository.findByStoryIdAndCharacterNameAndIsActiveTrue(testStoryId, "히로인"))
            .thenReturn(states)
        whenever(characterEventRepository.findByStoryIdAndCharacterNameAndEventTypeInOrderByTurnNumberDesc(
            eq(testStoryId), eq("히로인"), any()
        )).thenReturn(events)

        val analysis = ContextAnalysis(
            relevantCharacters = listOf("히로인"),
            neededStateTypes = listOf(StateType.INVENTORY, StateType.LOCATION),
            neededEventTypes = listOf(EventType.RELATIONSHIP_CHANGE),
            recentTurnWindow = 10
        )

        // when
        val result = contextService.loadCharacterContext(testStoryId, "히로인", analysis, 10)

        // then
        assertEquals("히로인", result.characterName)
        assertEquals(2, result.states.size)
        assertTrue(result.states.containsKey(StateType.INVENTORY))
        assertTrue(result.states.containsKey(StateType.LOCATION))
        assertEquals(1, result.recentEvents.size)
        assertEquals("호감도 상승", result.recentEvents[0].summary)
    }

    @Test
    fun `전체 컨텍스트 로드가 storyId 기반으로 동작한다`() {
        // given
        whenever(characterStateRepository.findByStoryIdAndCharacterNameAndIsActiveTrue(eq(testStoryId), any()))
            .thenReturn(emptyList())
        whenever(characterEventRepository.findByStoryIdAndCharacterNameAndEventTypeInOrderByTurnNumberDesc(
            eq(testStoryId), any(), any()
        )).thenReturn(emptyList())

        val analysis = ContextAnalysis(
            relevantCharacters = listOf("히로인"),
            neededStateTypes = listOf(StateType.INVENTORY),
            neededEventTypes = listOf(EventType.MAJOR_EVENT)
        )

        // when
        val result = contextService.loadContext(testStoryId, analysis, 10)

        // then
        assertEquals(1, result.characterContexts.size)
        assertEquals("히로인", result.characterContexts[0].characterName)
    }

    @Test
    fun `컨텍스트 프롬프트가 올바르게 생성된다`() {
        // given
        val characterContexts = listOf(
            CharacterContext(
                characterName = "히로인",
                states = mapOf(
                    StateType.INVENTORY to listOf(
                        StateEntry("검", "철검", "상점 구매"),
                        StateEntry("포션", "HP 포션")
                    ),
                    StateType.LOCATION to listOf(
                        StateEntry("현재위치", "마을 광장")
                    )
                ),
                recentEvents = listOf(
                    EventEntry(8, EventType.RELATIONSHIP_CHANGE, "호감도 상승")
                )
            )
        )

        // when
        val result = contextService.buildContextPrompt(characterContexts)

        // then
        assertTrue(result.contains("## 캐릭터 상태 정보"))
        assertTrue(result.contains("### 히로인"))
        assertTrue(result.contains("**소지품:**"))
        assertTrue(result.contains("- 검: 철검 (상점 구매)"))
        assertTrue(result.contains("- 포션: HP 포션"))
        assertTrue(result.contains("**위치:**"))
        assertTrue(result.contains("- 현재위치: 마을 광장"))
        assertTrue(result.contains("**최근 이벤트:**"))
        assertTrue(result.contains("관계변화: 호감도 상승"))
    }

    @Test
    fun `빈 컨텍스트이면 빈 문자열을 반환한다`() {
        // given
        val emptyContexts = listOf(
            CharacterContext("히로인", emptyMap(), emptyList())
        )

        // when
        val result = contextService.buildContextPrompt(emptyContexts)

        // then
        assertEquals("", result)
    }

    @Test
    fun `턴 윈도우 밖의 이벤트는 제외된다`() {
        // given
        val events = listOf(
            CharacterEvent(id = 1L, scenarioId = 1L, storyId = testStoryId, characterName = "히로인",
                turnNumber = 5, eventType = EventType.MAJOR_EVENT,
                summary = "오래된 이벤트"),
            CharacterEvent(id = 2L, scenarioId = 1L, storyId = testStoryId, characterName = "히로인",
                turnNumber = 18, eventType = EventType.MAJOR_EVENT,
                summary = "최근 이벤트")
        )
        whenever(characterEventRepository.findByStoryIdAndCharacterNameAndEventTypeInOrderByTurnNumberDesc(
            eq(testStoryId), eq("히로인"), any()
        )).thenReturn(events)

        val analysis = ContextAnalysis(
            relevantCharacters = listOf("히로인"),
            neededStateTypes = emptyList(),
            neededEventTypes = listOf(EventType.MAJOR_EVENT),
            recentTurnWindow = 10
        )

        // when
        val result = contextService.loadCharacterContext(testStoryId, "히로인", analysis, 20)

        // then
        assertEquals(1, result.recentEvents.size)
        assertEquals("최근 이벤트", result.recentEvents[0].summary)
    }

    @Test
    fun `여러 캐릭터의 컨텍스트가 각각 로드된다`() {
        // given
        val heroine = listOf(
            CharacterState(id = 1L, scenarioId = 1L, storyId = testStoryId, characterName = "히로인",
                stateType = StateType.LOCATION, stateKey = "현재위치", stateValue = "마을")
        )
        val companion = listOf(
            CharacterState(id = 2L, scenarioId = 1L, storyId = testStoryId, characterName = "동료",
                stateType = StateType.LOCATION, stateKey = "현재위치", stateValue = "숲")
        )

        whenever(characterStateRepository.findByStoryIdAndCharacterNameAndIsActiveTrue(testStoryId, "히로인"))
            .thenReturn(heroine)
        whenever(characterStateRepository.findByStoryIdAndCharacterNameAndIsActiveTrue(testStoryId, "동료"))
            .thenReturn(companion)
        whenever(characterEventRepository.findByStoryIdAndCharacterNameAndEventTypeInOrderByTurnNumberDesc(
            eq(testStoryId), any(), any()
        )).thenReturn(emptyList())

        val analysis = ContextAnalysis(
            relevantCharacters = listOf("히로인", "동료"),
            neededStateTypes = listOf(StateType.LOCATION),
            neededEventTypes = listOf(EventType.MAJOR_EVENT)
        )

        // when
        val result = contextService.loadContext(testStoryId, analysis, 10)

        // then
        assertEquals(2, result.characterContexts.size)
        val heroineCtx = result.characterContexts.find { it.characterName == "히로인" }!!
        val companionCtx = result.characterContexts.find { it.characterName == "동료" }!!
        assertEquals("마을", heroineCtx.states[StateType.LOCATION]!![0].value)
        assertEquals("숲", companionCtx.states[StateType.LOCATION]!![0].value)
    }
}
