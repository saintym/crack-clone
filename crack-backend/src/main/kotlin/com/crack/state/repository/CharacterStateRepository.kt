package com.crack.state.repository

import com.crack.state.entity.CharacterState
import com.crack.state.entity.StateType
import org.springframework.data.jpa.repository.JpaRepository

interface CharacterStateRepository : JpaRepository<CharacterState, Long> {
    fun findByScenarioIdAndCharacterNameAndIsActiveTrue(
        scenarioId: Long, characterName: String
    ): List<CharacterState>

    fun findByScenarioIdAndStateTypeAndIsActiveTrue(
        scenarioId: Long, stateType: StateType
    ): List<CharacterState>

    fun findByScenarioIdAndCharacterNameAndStateTypeAndStateKey(
        scenarioId: Long, characterName: String, stateType: StateType, stateKey: String
    ): CharacterState?

    fun findByScenarioIdAndIsActiveTrue(scenarioId: Long): List<CharacterState>

    fun findByStoryIdAndCharacterNameAndIsActiveTrue(
        storyId: Long, characterName: String
    ): List<CharacterState>

    fun findByStoryIdAndStateTypeAndIsActiveTrue(
        storyId: Long, stateType: StateType
    ): List<CharacterState>

    fun findByStoryIdAndCharacterNameAndStateTypeAndStateKey(
        storyId: Long, characterName: String, stateType: StateType, stateKey: String
    ): CharacterState?

    fun findByStoryIdAndIsActiveTrue(storyId: Long): List<CharacterState>
}
