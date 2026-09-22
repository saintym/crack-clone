package com.crack.chat.api

import com.crack.chat.flow.GenerationInProgressException
import com.crack.global.exception.BadRequestException
import com.crack.global.exception.ErrorResponse
import com.crack.global.exception.NotFoundException
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

/**
 * [StoryMessageController] 전용 예외 처리. 전역 핸들러보다 먼저 적용된다.
 *
 * Content-Type을 `application/json`으로 **미리 정해** 둔다. SSE 요청은 보통 `Accept: text/event-stream`을 보내는데,
 * 미리 정하지 않으면 JSON 에러 본문과 협상이 실패해 상태 코드가 바뀐다.
 */
@RestControllerAdvice(assignableTypes = [StoryMessageController::class])
@Order(Ordered.HIGHEST_PRECEDENCE)
class StoryMessageExceptionHandler {

    @ExceptionHandler(GenerationInProgressException::class)
    fun handleConflict(e: GenerationInProgressException) = error(HttpStatus.CONFLICT, e.message)

    @ExceptionHandler(BadRequestException::class)
    fun handleBadRequest(e: BadRequestException) = error(HttpStatus.BAD_REQUEST, e.message)

    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun handleUnreadable(e: HttpMessageNotReadableException) = error(HttpStatus.BAD_REQUEST, "요청 본문을 읽을 수 없습니다")

    @ExceptionHandler(NotFoundException::class)
    fun handleNotFound(e: NotFoundException) = error(HttpStatus.NOT_FOUND, e.message)

    private fun error(status: HttpStatus, message: String?): ResponseEntity<ErrorResponse> =
        ResponseEntity.status(status)
            .contentType(MediaType.APPLICATION_JSON)
            .body(ErrorResponse(message ?: status.reasonPhrase, status.value()))
}
