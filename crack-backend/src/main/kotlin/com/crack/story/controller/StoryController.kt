package com.crack.story.controller

import com.crack.story.dto.StoryCreateRequest
import com.crack.story.dto.StoryResponse
import com.crack.story.service.StoryService
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/scenarios/{scenarioId}/stories")
class StoryController(
    private val storyService: StoryService
) {
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(
        @PathVariable scenarioId: Long,
        @RequestBody request: StoryCreateRequest
    ): StoryResponse = storyService.create(scenarioId, request)

    @GetMapping
    fun listByScenario(@PathVariable scenarioId: Long): List<StoryResponse> =
        storyService.findByScenarioId(scenarioId)

    @GetMapping("/{storyId}")
    fun getById(
        @PathVariable scenarioId: Long,
        @PathVariable storyId: Long
    ): StoryResponse = storyService.findById(storyId)

    @PatchMapping("/{storyId}/archive")
    fun archive(
        @PathVariable scenarioId: Long,
        @PathVariable storyId: Long
    ): StoryResponse = storyService.archive(storyId)

    @DeleteMapping("/{storyId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(
        @PathVariable scenarioId: Long,
        @PathVariable storyId: Long
    ) = storyService.delete(storyId)

    @PostMapping("/{storyId}/branch")
    @ResponseStatus(HttpStatus.CREATED)
    fun branch(
        @PathVariable scenarioId: Long,
        @PathVariable storyId: Long,
        @RequestBody body: Map<String, Any>
    ): StoryResponse {
        val messageIndex = (body["messageIndex"] as Number).toInt()
        val title = body["title"] as String
        return storyService.branch(storyId, messageIndex, title)
    }
}

@RestController
@RequestMapping("/api/stories")
class StoryDirectController(
    private val storyService: StoryService
) {
    @GetMapping("/{storyId}")
    fun getById(@PathVariable storyId: Long): StoryResponse =
        storyService.findById(storyId)

    @PostMapping("/{storyId}/branch")
    @ResponseStatus(HttpStatus.CREATED)
    fun branch(
        @PathVariable storyId: Long,
        @RequestBody body: Map<String, Any>
    ): StoryResponse {
        val messageIndex = (body["messageIndex"] as Number).toInt()
        val title = body["title"] as String
        return storyService.branch(storyId, messageIndex, title)
    }
}
