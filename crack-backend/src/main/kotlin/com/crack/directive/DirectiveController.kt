package com.crack.directive

import com.crack.global.exception.ErrorResponse
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.bind.annotation.RestControllerAdvice

/** `POST /directives` body */
data class CreateDirectiveRequest(val text: String? = null, val enabled: Boolean? = null)

/** `PATCH /directives/{id}` body. 준 필드만 바꾼다. */
data class UpdateDirectiveRequest(val text: String? = null, val enabled: Boolean? = null)

/** 지속 OOC 지시 API (DESIGN.md §8.1). */
@RestController
@RequestMapping("/api/stories/{storyId}/directives")
class DirectiveController(
    private val directiveService: DirectiveService,
) {

    @GetMapping
    fun list(@PathVariable storyId: Long): List<Directive> = directiveService.list(storyId)

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun add(@PathVariable storyId: Long, @RequestBody request: CreateDirectiveRequest): Directive =
        directiveService.add(storyId, request.text, request.enabled)

    @PatchMapping("/{id}")
    fun update(
        @PathVariable storyId: Long,
        @PathVariable id: String,
        @RequestBody request: UpdateDirectiveRequest,
    ): Directive = directiveService.update(storyId, id, request.text, request.enabled)

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(@PathVariable storyId: Long, @PathVariable id: String) = directiveService.delete(storyId, id)
}

/** 깨진 `directives.json`은 덮어쓰지 않고 500으로 알린다. */
@RestControllerAdvice
class DirectiveExceptionHandler {
    private val log = LoggerFactory.getLogger(javaClass)

    @ExceptionHandler(DirectiveFileException::class)
    fun handleBrokenFile(e: DirectiveFileException): ResponseEntity<ErrorResponse> {
        log.error("지시 파일 오류", e)
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
            .contentType(MediaType.APPLICATION_JSON)
            .body(ErrorResponse(e.message ?: "지시 파일 오류", HttpStatus.INTERNAL_SERVER_ERROR.value()))
    }
}
