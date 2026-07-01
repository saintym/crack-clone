package com.crack.state.controller

import com.crack.state.dto.*
import com.crack.state.entity.EventType
import com.crack.state.entity.StateType
import com.crack.state.service.StateService
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/stories/{storyId}")
class StateController(
    private val stateService: StateService
) {
    // === CharacterState endpoints ===

    @PostMapping("/states")
    @ResponseStatus(HttpStatus.CREATED)
    fun createState(
        @PathVariable storyId: Long,
        @RequestBody request: CharacterStateCreateRequest
    ): CharacterStateResponse = stateService.createState(storyId, 0, request) // scenarioId resolved internally if needed

    @PutMapping("/states/{stateId}")
    fun updateState(
        @PathVariable storyId: Long,
        @PathVariable stateId: Long,
        @RequestBody request: CharacterStateUpdateRequest
    ): CharacterStateResponse = stateService.updateState(stateId, request)

    @GetMapping("/states")
    fun getActiveStates(
        @PathVariable storyId: Long
    ): List<CharacterStateResponse> = stateService.getActiveStates(storyId)

    @GetMapping("/states/character/{characterName}")
    fun getStatesByCharacter(
        @PathVariable storyId: Long,
        @PathVariable characterName: String
    ): List<CharacterStateResponse> = stateService.getStatesByCharacter(storyId, characterName)

    @GetMapping("/states/type/{stateType}")
    fun getStatesByType(
        @PathVariable storyId: Long,
        @PathVariable stateType: StateType
    ): List<CharacterStateResponse> = stateService.getStatesByType(storyId, stateType)

    @DeleteMapping("/states/{stateId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun deactivateState(
        @PathVariable storyId: Long,
        @PathVariable stateId: Long
    ) = stateService.deactivateState(stateId)

    // === CharacterEvent endpoints ===

    @PostMapping("/events")
    @ResponseStatus(HttpStatus.CREATED)
    fun createEvent(
        @PathVariable storyId: Long,
        @RequestBody request: CharacterEventCreateRequest
    ): CharacterEventResponse = stateService.createEvent(storyId, 0, request)

    @GetMapping("/events/character/{characterName}")
    fun getEventsByCharacter(
        @PathVariable storyId: Long,
        @PathVariable characterName: String
    ): List<CharacterEventResponse> = stateService.getEventsByCharacter(storyId, characterName)

    @GetMapping("/events/character/{characterName}/recent")
    fun getRecentEvents(
        @PathVariable storyId: Long,
        @PathVariable characterName: String,
        @RequestParam fromTurn: Int
    ): List<CharacterEventResponse> = stateService.getRecentEvents(storyId, characterName, fromTurn)

    @GetMapping("/events/character/{characterName}/type")
    fun getEventsByType(
        @PathVariable storyId: Long,
        @PathVariable characterName: String,
        @RequestParam eventTypes: List<EventType>
    ): List<CharacterEventResponse> = stateService.getEventsByType(storyId, characterName, eventTypes)

    @GetMapping("/events/turns")
    fun getEventsByTurnRange(
        @PathVariable storyId: Long,
        @RequestParam fromTurn: Int,
        @RequestParam toTurn: Int
    ): List<CharacterEventResponse> = stateService.getEventsByTurnRange(storyId, fromTurn, toTurn)
}
