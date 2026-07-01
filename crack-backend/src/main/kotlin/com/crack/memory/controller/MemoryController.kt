package com.crack.memory.controller

import com.crack.memory.dto.MustRememberResponse
import com.crack.memory.dto.MustRememberUpdateRequest
import com.crack.memory.dto.SummarizeResult
import com.crack.memory.dto.SummaryResponse
import com.crack.memory.service.MemoryService
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/stories/{storyId}/memory")
class MemoryController(
    private val memoryService: MemoryService
) {
    @GetMapping("/must-remember")
    fun getMustRemember(@PathVariable storyId: Long): MustRememberResponse {
        return MustRememberResponse(memoryService.readMustRemember(storyId))
    }

    @PutMapping("/must-remember")
    fun updateMustRemember(
        @PathVariable storyId: Long,
        @RequestBody request: MustRememberUpdateRequest
    ): MustRememberResponse {
        return MustRememberResponse(memoryService.updateMustRemember(storyId, request.content))
    }

    @GetMapping("/summaries")
    fun listSummaries(@PathVariable storyId: Long): List<SummaryResponse> {
        return memoryService.listSummaries(storyId)
    }

    @PostMapping("/summarize")
    fun summarize(@PathVariable storyId: Long): SummarizeResult {
        return memoryService.summarize(storyId)
    }
}
