package com.crack.context.dto

import com.crack.state.entity.EventType
import com.crack.state.entity.StateType

data class ContextAnalysis(
    val relevantCharacters: List<String>,
    val neededStateTypes: List<StateType>,
    val neededEventTypes: List<EventType>,
    val recentTurnWindow: Int = 10
)

data class CharacterContext(
    val characterName: String,
    val states: Map<StateType, List<StateEntry>>,
    val recentEvents: List<EventEntry>
)

data class StateEntry(
    val key: String,
    val value: String,
    val context: String? = null
)

data class EventEntry(
    val turnNumber: Int,
    val eventType: EventType,
    val summary: String
)

data class AssembledContext(
    val characterContexts: List<CharacterContext>,
    val contextPrompt: String
)
