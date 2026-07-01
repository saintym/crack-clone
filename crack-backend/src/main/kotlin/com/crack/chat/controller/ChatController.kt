package com.crack.chat.controller

import com.crack.chat.dto.ChatRequest
import com.crack.chat.dto.ParsedResponse
import com.crack.chat.service.ChatService
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.*
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter

@RestController
@RequestMapping("/api/stories/{storyId}/chat")
class ChatController(
    private val chatService: ChatService
) {
    @PostMapping(produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    fun chat(
        @PathVariable storyId: Long,
        @RequestBody request: ChatRequest
    ): SseEmitter {
        return chatService.streamChat(storyId, request)
    }

    @PostMapping("/complete")
    fun onComplete(
        @PathVariable storyId: Long,
        @RequestBody body: Map<String, String>
    ) {
        val response = body["response"] ?: return
        chatService.onResponseComplete(storyId, response)
    }

    @GetMapping("/history")
    fun getHistory(@PathVariable storyId: Long): Map<String, String> {
        return mapOf("content" to chatService.getChatHistory(storyId))
    }

    @PostMapping("/parse")
    fun parseResponse(@RequestBody body: Map<String, String>): ParsedResponse {
        val raw = body["response"] ?: ""
        return chatService.parseResponse(raw)
    }

    @PatchMapping("/messages/{messageIndex}")
    fun editMessage(
        @PathVariable storyId: Long,
        @PathVariable messageIndex: Int,
        @RequestBody body: Map<String, String>
    ): Map<String, Any> {
        val content = body["content"] ?: throw IllegalArgumentException("content is required")
        val messages = chatService.editMessage(storyId, messageIndex, content)
        return mapOf("messages" to messages)
    }

    @DeleteMapping("/messages/{messageIndex}")
    fun deleteMessagesFrom(
        @PathVariable storyId: Long,
        @PathVariable messageIndex: Int
    ): Map<String, Any> {
        val remaining = chatService.deleteMessagesFrom(storyId, messageIndex)
        return mapOf("messages" to remaining)
    }

    @PostMapping("/regenerate", produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    fun regenerate(
        @PathVariable storyId: Long,
        @RequestBody request: ChatRequest
    ): SseEmitter {
        return chatService.regenerate(storyId, request)
    }

    @PostMapping("/continue", produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    fun continueChat(
        @PathVariable storyId: Long,
        @RequestBody request: ChatRequest
    ): SseEmitter {
        return chatService.continueChat(storyId, request)
    }
}
