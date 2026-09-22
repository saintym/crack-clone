package com.crack.chat.api

import com.crack.chat.flow.ChatFlowService
import com.crack.chat.flow.StoryMessagesState
import com.crack.global.exception.BadRequestException
import com.crack.message.dto.MessageView
import com.crack.message.dto.TruncateResult
import com.crack.message.service.MessageExporter
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter

/**
 * 채팅 API (DESIGN.md §5.2). SSE 이벤트: `user` / `delta` / `done` / `error`.
 *
 * SSE 매핑에 `produces`를 걸지 않는다. 걸면 생성 전에 난 400/404/409를 JSON으로 돌려줄 때
 * `Accept: text/event-stream`과 충돌한다. 응답 Content-Type은 SseEmitter가 `text/event-stream`으로 정한다.
 */
@RestController
@RequestMapping("/api/stories/{storyId}/messages")
class StoryMessageController(
    private val chatFlowService: ChatFlowService,
    private val messageExporter: MessageExporter,
) {

    @GetMapping
    fun list(@PathVariable storyId: Long): StoryMessagesState = chatFlowService.state(storyId)

    @PostMapping
    fun send(@PathVariable storyId: Long, @RequestBody request: SendMessageRequest): SseEmitter =
        chatFlowService.send(storyId, request.content, request.provider, request.command)

    @PostMapping("/continue")
    fun continueStory(@PathVariable storyId: Long, @RequestBody(required = false) request: ContinueRequest?): SseEmitter =
        chatFlowService.continueStory(storyId, request?.provider)

    @PostMapping("/regenerate")
    fun regenerate(@PathVariable storyId: Long, @RequestBody(required = false) request: RegenerateRequest?): SseEmitter =
        chatFlowService.regenerate(storyId, request?.provider, request?.instruction, request?.messageId)

    @PutMapping("/{messageId}/variant")
    fun selectVariant(
        @PathVariable storyId: Long,
        @PathVariable messageId: Long,
        @RequestBody request: SelectVariantRequest,
    ): MessageView {
        val index = request.index ?: throw BadRequestException("index가 필요합니다")
        return chatFlowService.selectVariant(storyId, messageId, index)
    }

    @PatchMapping("/{messageId}")
    fun edit(
        @PathVariable storyId: Long,
        @PathVariable messageId: Long,
        @RequestBody request: EditMessageRequest,
    ): MessageView = chatFlowService.edit(storyId, messageId, request.content)

    @DeleteMapping("/{messageId}")
    fun truncateFrom(@PathVariable storyId: Long, @PathVariable messageId: Long): TruncateResult =
        chatFlowService.truncateFrom(storyId, messageId)

    @GetMapping("/export")
    fun export(@PathVariable storyId: Long): ResponseEntity<String> =
        ResponseEntity.ok()
            .contentType(MediaType("text", "markdown", Charsets.UTF_8))
            .body(messageExporter.export(storyId))
}
