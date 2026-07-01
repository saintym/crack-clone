package com.crack.state.service

import com.crack.global.exception.NotFoundException
import com.crack.state.dto.*
import com.crack.state.entity.CharacterEvent
import com.crack.state.entity.CharacterState
import com.crack.state.entity.EventType
import com.crack.state.entity.StateType
import com.crack.state.repository.CharacterEventRepository
import com.crack.state.repository.CharacterStateRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime

@Service
@Transactional(readOnly = true)
class StateService(
    private val characterStateRepository: CharacterStateRepository,
    private val characterEventRepository: CharacterEventRepository
) {
    // === CharacterState ===

    @Transactional
    fun createState(storyId: Long, scenarioId: Long, request: CharacterStateCreateRequest): CharacterStateResponse {
        val existing = characterStateRepository.findByStoryIdAndCharacterNameAndStateTypeAndStateKey(
            storyId, request.characterName, request.stateType, request.stateKey
        )
        if (existing != null) {
            existing.stateValue = request.stateValue
            existing.context = request.context
            existing.isActive = true
            existing.updatedAt = LocalDateTime.now()
            return CharacterStateResponse.from(existing)
        }

        val state = CharacterState(
            scenarioId = scenarioId,
            storyId = storyId,
            characterName = request.characterName,
            stateType = request.stateType,
            stateKey = request.stateKey,
            stateValue = request.stateValue,
            context = request.context,
            acquiredTurn = request.acquiredTurn
        )
        return CharacterStateResponse.from(characterStateRepository.save(state))
    }

    @Transactional
    fun updateState(stateId: Long, request: CharacterStateUpdateRequest): CharacterStateResponse {
        val state = characterStateRepository.findById(stateId)
            .orElseThrow { NotFoundException("State not found: $stateId") }

        state.stateValue = request.stateValue
        state.context = request.context
        state.isActive = request.isActive
        state.updatedAt = LocalDateTime.now()

        return CharacterStateResponse.from(state)
    }

    fun getActiveStates(storyId: Long): List<CharacterStateResponse> {
        return characterStateRepository.findByStoryIdAndIsActiveTrue(storyId)
            .map { CharacterStateResponse.from(it) }
    }

    fun getStatesByCharacter(storyId: Long, characterName: String): List<CharacterStateResponse> {
        return characterStateRepository.findByStoryIdAndCharacterNameAndIsActiveTrue(storyId, characterName)
            .map { CharacterStateResponse.from(it) }
    }

    fun getStatesByType(storyId: Long, stateType: StateType): List<CharacterStateResponse> {
        return characterStateRepository.findByStoryIdAndStateTypeAndIsActiveTrue(storyId, stateType)
            .map { CharacterStateResponse.from(it) }
    }

    @Transactional
    fun deactivateState(stateId: Long) {
        val state = characterStateRepository.findById(stateId)
            .orElseThrow { NotFoundException("State not found: $stateId") }
        state.isActive = false
        state.updatedAt = LocalDateTime.now()
    }

    // === CharacterEvent ===

    @Transactional
    fun createEvent(storyId: Long, scenarioId: Long, request: CharacterEventCreateRequest): CharacterEventResponse {
        val event = CharacterEvent(
            scenarioId = scenarioId,
            storyId = storyId,
            characterName = request.characterName,
            turnNumber = request.turnNumber,
            eventType = request.eventType,
            summary = request.summary,
            detail = request.detail
        )
        return CharacterEventResponse.from(characterEventRepository.save(event))
    }

    fun getEventsByCharacter(storyId: Long, characterName: String): List<CharacterEventResponse> {
        return characterEventRepository.findByStoryIdAndCharacterNameOrderByTurnNumberDesc(storyId, characterName)
            .map { CharacterEventResponse.from(it) }
    }

    fun getRecentEvents(storyId: Long, characterName: String, fromTurn: Int): List<CharacterEventResponse> {
        return characterEventRepository.findByStoryIdAndCharacterNameAndTurnNumberGreaterThanEqual(
            storyId, characterName, fromTurn
        ).map { CharacterEventResponse.from(it) }
    }

    fun getEventsByType(storyId: Long, characterName: String, eventTypes: List<EventType>): List<CharacterEventResponse> {
        return characterEventRepository.findByStoryIdAndCharacterNameAndEventTypeInOrderByTurnNumberDesc(
            storyId, characterName, eventTypes
        ).map { CharacterEventResponse.from(it) }
    }

    fun getEventsByTurnRange(storyId: Long, fromTurn: Int, toTurn: Int): List<CharacterEventResponse> {
        return characterEventRepository.findByStoryIdAndTurnNumberBetween(storyId, fromTurn, toTurn)
            .map { CharacterEventResponse.from(it) }
    }
}
