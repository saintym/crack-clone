package com.crack.document.story

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/** 스토리 문서 API (DESIGN.md §9). */
@RestController
@RequestMapping("/api/stories/{storyId}/documents")
class StoryDocumentController(
    private val storyDocumentService: StoryDocumentService,
) {
    @GetMapping
    fun list(@PathVariable storyId: Long): List<StoryDocumentSummary> =
        storyDocumentService.list(storyId)

    @GetMapping("/content")
    fun read(
        @PathVariable storyId: Long,
        @RequestParam(required = false) path: String?,
    ): StoryDocumentContent = storyDocumentService.read(storyId, path)

    @PutMapping("/content")
    fun write(
        @PathVariable storyId: Long,
        @RequestParam(required = false) path: String?,
        @RequestBody request: StoryDocumentUpdateRequest,
    ): StoryDocumentContent = storyDocumentService.write(storyId, path, request.content)
}
