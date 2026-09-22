package com.crack.status

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RestController

/** 인물 상태 패널 API (DESIGN.md §7.5). */
@RestController
class StoryStatusController(
    private val storyStatusService: StoryStatusService,
) {

    @GetMapping("/api/stories/{storyId}/status")
    fun status(@PathVariable storyId: Long): StoryStatusResponse = storyStatusService.status(storyId)
}
