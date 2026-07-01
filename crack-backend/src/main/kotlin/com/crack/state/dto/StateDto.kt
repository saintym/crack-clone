package com.crack.state.dto

import com.crack.state.entity.*
import java.time.LocalDateTime

// === CharacterState DTOs ===

data class CharacterStateCreateRequest(
    val characterName: String,
    val stateType: StateType,
    val stateKey: String,
    val stateValue: String,
    val context: String? = null,
    val acquiredTurn: Int? = null
)

data class CharacterStateUpdateRequest(
    val stateValue: String,
    val context: String? = null,
    val isActive: Boolean = true
)

data class CharacterStateResponse(
    val id: Long,
    val scenarioId: Long,
    val characterName: String,
    val stateType: StateType,
    val stateKey: String,
    val stateValue: String,
    val context: String?,
    val acquiredTurn: Int?,
    val isActive: Boolean,
    val updatedAt: LocalDateTime
) {
    companion object {
        fun from(state: CharacterState) = CharacterStateResponse(
            id = state.id,
            scenarioId = state.scenarioId,
            characterName = state.characterName,
            stateType = state.stateType,
            stateKey = state.stateKey,
            stateValue = state.stateValue,
            context = state.context,
            acquiredTurn = state.acquiredTurn,
            isActive = state.isActive,
            updatedAt = state.updatedAt
        )
    }
}

// === CharacterEvent DTOs ===

data class CharacterEventCreateRequest(
    val characterName: String,
    val turnNumber: Int,
    val eventType: EventType,
    val summary: String,
    val detail: String? = null
)

data class CharacterEventResponse(
    val id: Long,
    val scenarioId: Long,
    val characterName: String,
    val turnNumber: Int,
    val eventType: EventType,
    val summary: String,
    val detail: String?,
    val createdAt: LocalDateTime
) {
    companion object {
        fun from(event: CharacterEvent) = CharacterEventResponse(
            id = event.id,
            scenarioId = event.scenarioId,
            characterName = event.characterName,
            turnNumber = event.turnNumber,
            eventType = event.eventType,
            summary = event.summary,
            detail = event.detail,
            createdAt = event.createdAt
        )
    }
}
