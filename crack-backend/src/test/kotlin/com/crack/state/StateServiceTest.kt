package com.crack.state

import com.crack.global.exception.NotFoundException
import com.crack.state.dto.*
import com.crack.state.entity.*
import com.crack.state.repository.CharacterEventRepository
import com.crack.state.repository.CharacterStateRepository
import com.crack.state.service.StateService
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.*
import java.util.*

@ExtendWith(MockitoExtension::class)
class StateServiceTest {

    private lateinit var characterStateRepository: CharacterStateRepository
    private lateinit var characterEventRepository: CharacterEventRepository
    private lateinit var stateService: StateService

    private val testStoryId = 1L
    private val testScenarioId = 1L

    @BeforeEach
    fun setUp() {
        characterStateRepository = mock()
        characterEventRepository = mock()
        stateService = StateService(characterStateRepository, characterEventRepository)
    }

    // === CharacterState 테스트 ===

    @Test
    fun `새 상태를 생성하면 DB에 저장된다`() {
        // given
        whenever(characterStateRepository.findByStoryIdAndCharacterNameAndStateTypeAndStateKey(
            testStoryId, "히로인", StateType.INVENTORY, "검"
        )).thenReturn(null)
        whenever(characterStateRepository.save(any<CharacterState>())).thenAnswer { invocation ->
            val state = invocation.getArgument<CharacterState>(0)
            CharacterState(
                id = 1L,
                scenarioId = state.scenarioId,
                storyId = state.storyId,
                characterName = state.characterName,
                stateType = state.stateType,
                stateKey = state.stateKey,
                stateValue = state.stateValue,
                context = state.context,
                acquiredTurn = state.acquiredTurn
            )
        }

        // when
        val result = stateService.createState(testStoryId, testScenarioId, CharacterStateCreateRequest(
            characterName = "히로인",
            stateType = StateType.INVENTORY,
            stateKey = "검",
            stateValue = "낡은 철검",
            context = "상점에서 구매",
            acquiredTurn = 5
        ))

        // then
        assertEquals("히로인", result.characterName)
        assertEquals(StateType.INVENTORY, result.stateType)
        assertEquals("검", result.stateKey)
        assertEquals("낡은 철검", result.stateValue)
        assertEquals("상점에서 구매", result.context)
        assertEquals(5, result.acquiredTurn)
        assertTrue(result.isActive)
        verify(characterStateRepository).save(any<CharacterState>())
    }

    @Test
    fun `동일 키로 상태 생성 시 기존 상태가 업데이트된다`() {
        // given
        val existingState = CharacterState(
            id = 1L,
            scenarioId = testScenarioId,
            storyId = testStoryId,
            characterName = "히로인",
            stateType = StateType.LOCATION,
            stateKey = "현재위치",
            stateValue = "마을",
            isActive = false
        )
        whenever(characterStateRepository.findByStoryIdAndCharacterNameAndStateTypeAndStateKey(
            testStoryId, "히로인", StateType.LOCATION, "현재위치"
        )).thenReturn(existingState)

        // when
        val result = stateService.createState(testStoryId, testScenarioId, CharacterStateCreateRequest(
            characterName = "히로인",
            stateType = StateType.LOCATION,
            stateKey = "현재위치",
            stateValue = "숲",
            context = "이동함"
        ))

        // then
        assertEquals("숲", result.stateValue)
        assertEquals("이동함", result.context)
        assertTrue(result.isActive)
        verify(characterStateRepository, never()).save(any<CharacterState>())
    }

    @Test
    fun `상태 업데이트가 올바르게 반영된다`() {
        // given
        val state = CharacterState(
            id = 1L, scenarioId = testScenarioId, storyId = testStoryId, characterName = "히로인",
            stateType = StateType.INVENTORY, stateKey = "검",
            stateValue = "낡은 철검"
        )
        whenever(characterStateRepository.findById(1L)).thenReturn(Optional.of(state))

        // when
        val result = stateService.updateState(1L, CharacterStateUpdateRequest(
            stateValue = "강화된 철검",
            context = "대장장이가 강화"
        ))

        // then
        assertEquals("강화된 철검", result.stateValue)
        assertEquals("대장장이가 강화", result.context)
    }

    @Test
    fun `존재하지 않는 상태 업데이트 시 예외 발생`() {
        // given
        whenever(characterStateRepository.findById(999L)).thenReturn(Optional.empty())

        // when & then
        assertThrows<NotFoundException> {
            stateService.updateState(999L, CharacterStateUpdateRequest(
                stateValue = "값"
            ))
        }
    }

    @Test
    fun `활성 상태만 조회된다`() {
        // given
        val states = listOf(
            CharacterState(id = 1L, scenarioId = testScenarioId, storyId = testStoryId, characterName = "히로인",
                stateType = StateType.INVENTORY, stateKey = "검", stateValue = "철검"),
            CharacterState(id = 2L, scenarioId = testScenarioId, storyId = testStoryId, characterName = "히로인",
                stateType = StateType.LOCATION, stateKey = "현재위치", stateValue = "마을")
        )
        whenever(characterStateRepository.findByStoryIdAndIsActiveTrue(testStoryId)).thenReturn(states)

        // when
        val result = stateService.getActiveStates(testStoryId)

        // then
        assertEquals(2, result.size)
        assertEquals("검", result[0].stateKey)
        assertEquals("현재위치", result[1].stateKey)
    }

    @Test
    fun `캐릭터별 상태를 조회할 수 있다`() {
        // given
        val states = listOf(
            CharacterState(id = 1L, scenarioId = testScenarioId, storyId = testStoryId, characterName = "히로인",
                stateType = StateType.SKILL, stateKey = "마법", stateValue = "초급 화염")
        )
        whenever(characterStateRepository.findByStoryIdAndCharacterNameAndIsActiveTrue(testStoryId, "히로인"))
            .thenReturn(states)

        // when
        val result = stateService.getStatesByCharacter(testStoryId, "히로인")

        // then
        assertEquals(1, result.size)
        assertEquals(StateType.SKILL, result[0].stateType)
        assertEquals("마법", result[0].stateKey)
    }

    @Test
    fun `상태 타입별 조회가 동작한다`() {
        // given
        val states = listOf(
            CharacterState(id = 1L, scenarioId = testScenarioId, storyId = testStoryId, characterName = "히로인",
                stateType = StateType.INVENTORY, stateKey = "검", stateValue = "철검"),
            CharacterState(id = 2L, scenarioId = testScenarioId, storyId = testStoryId, characterName = "동료",
                stateType = StateType.INVENTORY, stateKey = "방패", stateValue = "나무방패")
        )
        whenever(characterStateRepository.findByStoryIdAndStateTypeAndIsActiveTrue(testStoryId, StateType.INVENTORY))
            .thenReturn(states)

        // when
        val result = stateService.getStatesByType(testStoryId, StateType.INVENTORY)

        // then
        assertEquals(2, result.size)
    }

    @Test
    fun `상태 비활성화 시 isActive가 false가 된다`() {
        // given
        val state = CharacterState(
            id = 1L, scenarioId = testScenarioId, storyId = testStoryId, characterName = "히로인",
            stateType = StateType.INVENTORY, stateKey = "포션",
            stateValue = "HP 포션", isActive = true
        )
        whenever(characterStateRepository.findById(1L)).thenReturn(Optional.of(state))

        // when
        stateService.deactivateState(1L)

        // then
        assertFalse(state.isActive)
    }

    // === CharacterEvent 테스트 ===

    @Test
    fun `이벤트를 생성하면 DB에 저장된다`() {
        // given
        whenever(characterEventRepository.save(any<CharacterEvent>())).thenAnswer { invocation ->
            val event = invocation.getArgument<CharacterEvent>(0)
            CharacterEvent(
                id = 1L,
                scenarioId = event.scenarioId,
                storyId = event.storyId,
                characterName = event.characterName,
                turnNumber = event.turnNumber,
                eventType = event.eventType,
                summary = event.summary,
                detail = event.detail
            )
        }

        // when
        val result = stateService.createEvent(testStoryId, testScenarioId, CharacterEventCreateRequest(
            characterName = "히로인",
            turnNumber = 10,
            eventType = EventType.RELATIONSHIP_CHANGE,
            summary = "주인공에 대한 호감도가 상승했다",
            detail = "위기 상황에서 주인공이 구해준 것에 감사함"
        ))

        // then
        assertEquals("히로인", result.characterName)
        assertEquals(10, result.turnNumber)
        assertEquals(EventType.RELATIONSHIP_CHANGE, result.eventType)
        assertEquals("주인공에 대한 호감도가 상승했다", result.summary)
        assertNotNull(result.detail)
        verify(characterEventRepository).save(any<CharacterEvent>())
    }

    @Test
    fun `캐릭터별 이벤트를 턴 역순으로 조회할 수 있다`() {
        // given
        val events = listOf(
            CharacterEvent(id = 2L, scenarioId = testScenarioId, storyId = testStoryId, characterName = "히로인",
                turnNumber = 20, eventType = EventType.MAJOR_EVENT,
                summary = "중요 전투 참전"),
            CharacterEvent(id = 1L, scenarioId = testScenarioId, storyId = testStoryId, characterName = "히로인",
                turnNumber = 10, eventType = EventType.RELATIONSHIP_CHANGE,
                summary = "호감도 상승")
        )
        whenever(characterEventRepository.findByStoryIdAndCharacterNameOrderByTurnNumberDesc(testStoryId, "히로인"))
            .thenReturn(events)

        // when
        val result = stateService.getEventsByCharacter(testStoryId, "히로인")

        // then
        assertEquals(2, result.size)
        assertEquals(20, result[0].turnNumber)
        assertEquals(10, result[1].turnNumber)
    }

    @Test
    fun `특정 턴 이후 이벤트를 조회할 수 있다`() {
        // given
        val events = listOf(
            CharacterEvent(id = 3L, scenarioId = testScenarioId, storyId = testStoryId, characterName = "히로인",
                turnNumber = 15, eventType = EventType.KNOWLEDGE_GAIN,
                summary = "비밀 발견")
        )
        whenever(characterEventRepository.findByStoryIdAndCharacterNameAndTurnNumberGreaterThanEqual(
            testStoryId, "히로인", 15
        )).thenReturn(events)

        // when
        val result = stateService.getRecentEvents(testStoryId, "히로인", 15)

        // then
        assertEquals(1, result.size)
        assertEquals("비밀 발견", result[0].summary)
    }

    @Test
    fun `이벤트 타입별 필터링이 동작한다`() {
        // given
        val events = listOf(
            CharacterEvent(id = 1L, scenarioId = testScenarioId, storyId = testStoryId, characterName = "히로인",
                turnNumber = 10, eventType = EventType.RELATIONSHIP_CHANGE,
                summary = "호감도 상승"),
            CharacterEvent(id = 2L, scenarioId = testScenarioId, storyId = testStoryId, characterName = "히로인",
                turnNumber = 5, eventType = EventType.PERSONALITY_SHIFT,
                summary = "성격 변화")
        )
        val types = listOf(EventType.RELATIONSHIP_CHANGE, EventType.PERSONALITY_SHIFT)
        whenever(characterEventRepository.findByStoryIdAndCharacterNameAndEventTypeInOrderByTurnNumberDesc(
            testStoryId, "히로인", types
        )).thenReturn(events)

        // when
        val result = stateService.getEventsByType(testStoryId, "히로인", types)

        // then
        assertEquals(2, result.size)
    }

    @Test
    fun `턴 범위로 이벤트를 조회할 수 있다`() {
        // given
        val events = listOf(
            CharacterEvent(id = 1L, scenarioId = testScenarioId, storyId = testStoryId, characterName = "히로인",
                turnNumber = 5, eventType = EventType.MAJOR_EVENT,
                summary = "전투 발생"),
            CharacterEvent(id = 2L, scenarioId = testScenarioId, storyId = testStoryId, characterName = "동료",
                turnNumber = 8, eventType = EventType.KNOWLEDGE_GAIN,
                summary = "정보 획득")
        )
        whenever(characterEventRepository.findByStoryIdAndTurnNumberBetween(testStoryId, 1, 10))
            .thenReturn(events)

        // when
        val result = stateService.getEventsByTurnRange(testStoryId, 1, 10)

        // then
        assertEquals(2, result.size)
        assertEquals("히로인", result[0].characterName)
        assertEquals("동료", result[1].characterName)
    }
}
