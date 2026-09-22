package com.crack.memory.record

import com.crack.global.exception.ErrorResponse
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.bind.annotation.RestControllerAdvice

/**
 * 기억 기록 API (DESIGN.md §7.2). 옛 `memory/controller`(`/must-remember`, `/summaries`, `/summarize`)와 경로가 겹치지 않는다.
 */
@RestController
@RequestMapping("/api/stories/{storyId}/memory")
class MemoryRecordController(
    private val service: MemoryRecordService,
) {

    /** 수동 기록(`/기록` 명령). 결과는 기다리지 않는다. */
    @PostMapping("/record")
    fun record(@PathVariable storyId: Long): TriggerResponse = service.trigger(storyId, RecordReason.MANUAL)

    @GetMapping("/records")
    fun list(@PathVariable storyId: Long): List<MemoryRecordSummary> = service.list(storyId)

    @GetMapping("/records/{recordId}")
    fun detail(@PathVariable storyId: Long, @PathVariable recordId: Long): MemoryRecordDetail =
        service.detail(storyId, recordId)

    @PostMapping("/records/{recordId}/revert")
    fun revert(@PathVariable storyId: Long, @PathVariable recordId: Long): MemoryRecordSummary =
        service.revert(storyId, recordId)

    @PostMapping("/records/seen")
    fun markSeen(@PathVariable storyId: Long): ResponseEntity<Void> {
        service.markSeen(storyId)
        return ResponseEntity.noContent().build()
    }
}

/** [MemoryRecordController] 전용: 실행 중 기록과 충돌하면 409 */
@RestControllerAdvice(assignableTypes = [MemoryRecordController::class])
@Order(Ordered.HIGHEST_PRECEDENCE)
class MemoryRecordExceptionHandler {

    @ExceptionHandler(MemoryRecordConflictException::class)
    fun handleConflict(e: MemoryRecordConflictException): ResponseEntity<ErrorResponse> =
        ResponseEntity.status(HttpStatus.CONFLICT)
            .contentType(MediaType.APPLICATION_JSON)
            .body(ErrorResponse(e.message ?: "Conflict", HttpStatus.CONFLICT.value()))
}
