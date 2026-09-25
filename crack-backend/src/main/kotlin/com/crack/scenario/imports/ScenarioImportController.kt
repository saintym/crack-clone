package com.crack.scenario.imports

import com.crack.global.exception.BadRequestException
import com.crack.global.exception.ErrorResponse
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter

/**
 * URL 시나리오 가져오기 API (DESIGN.md §11.2).
 *
 * SSE 매핑에 `produces`를 걸지 않는다. 걸면 생성 전 400/404를 JSON으로 돌려줄 때
 * `Accept: text/event-stream`과 부딪힌다(T07에서 겪은 것과 같다).
 */
@RestController
@RequestMapping("/api/scenarios/import")
class ScenarioImportController(
    private val service: ScenarioImportService,
) {

    @PostMapping("/analyze")
    fun analyze(
        @RequestBody request: ImportAnalyzeRequest,
        @RequestParam(required = false) provider: String?,
    ): ImportAnalyzeResponse {
        if (request.url.isBlank()) throw BadRequestException("주소를 입력하세요")
        return service.analyze(request.url, provider)
    }

    @PostMapping("/{jobId}/confirm")
    fun confirm(
        @PathVariable jobId: String,
        @RequestBody request: ImportConfirmRequest,
        @RequestParam(required = false) provider: String?,
    ): SseEmitter = service.confirm(jobId, request, provider)

    /** LLM이 계약과 다른 형식을 돌려준 것은 우리 잘못도, 사용자 입력 잘못도 아니다 → 502. */
    @ExceptionHandler(ImportFormatException::class)
    fun handleFormat(e: ImportFormatException): ResponseEntity<ErrorResponse> =
        ResponseEntity.status(HttpStatus.BAD_GATEWAY)
            .body(ErrorResponse(e.message ?: "AI 응답 형식이 올바르지 않습니다", 502))
}
