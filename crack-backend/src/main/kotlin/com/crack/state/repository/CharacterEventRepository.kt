package com.crack.state.repository

import com.crack.state.entity.CharacterEvent
import com.crack.state.entity.EventType
import org.springframework.data.jpa.repository.JpaRepository

interface CharacterEventRepository : JpaRepository<CharacterEvent, Long> {
    fun findByScenarioIdAndCharacterNameOrderByTurnNumberDesc(
        scenarioId: Long, characterName: String
    ): List<CharacterEvent>

    fun findByScenarioIdAndCharacterNameAndTurnNumberGreaterThanEqual(
        scenarioId: Long, characterName: String, turnNumber: Int
    ): List<CharacterEvent>

    fun findByScenarioIdAndCharacterNameAndEventTypeInOrderByTurnNumberDesc(
        scenarioId: Long, characterName: String, eventTypes: List<EventType>
    ): List<CharacterEvent>

    fun findByScenarioIdAndTurnNumberBetween(
        scenarioId: Long, fromTurn: Int, toTurn: Int
    ): List<CharacterEvent>

    fun findByStoryIdAndCharacterNameOrderByTurnNumberDesc(
        storyId: Long, characterName: String
    ): List<CharacterEvent>

    fun findByStoryIdAndCharacterNameAndTurnNumberGreaterThanEqual(
        storyId: Long, characterName: String, turnNumber: Int
    ): List<CharacterEvent>

    fun findByStoryIdAndCharacterNameAndEventTypeInOrderByTurnNumberDesc(
        storyId: Long, characterName: String, eventTypes: List<EventType>
    ): List<CharacterEvent>

    fun findByStoryIdAndTurnNumberBetween(
        storyId: Long, fromTurn: Int, toTurn: Int
    ): List<CharacterEvent>
}
