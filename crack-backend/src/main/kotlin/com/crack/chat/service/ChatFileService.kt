package com.crack.chat.service

import org.springframework.stereotype.Service
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

@Service
class ChatFileService {

    fun appendUserMessage(storyPath: Path, message: String) {
        val chatFile = chatLatestPath(storyPath)
        ensureChatFile(chatFile)
        val entry = "\n## USER\n$message\n"
        Files.writeString(chatFile, entry, StandardOpenOption.APPEND)
    }

    fun appendAssistantMessage(storyPath: Path, response: String) {
        val chatFile = chatLatestPath(storyPath)
        ensureChatFile(chatFile)
        val entry = "\n## ASSISTANT\n$response\n"
        Files.writeString(chatFile, entry, StandardOpenOption.APPEND)
    }

    fun readChatLatest(storyPath: Path): String {
        val chatFile = chatLatestPath(storyPath)
        return if (Files.exists(chatFile)) Files.readString(chatFile) else ""
    }

    fun resetChatLatest(storyPath: Path) {
        val chatFile = chatLatestPath(storyPath)
        Files.writeString(chatFile, "# 최근 대화\n\n")
    }

    fun archiveChat(storyPath: Path, fromTurn: Int, toTurn: Int, content: String) {
        val archiveDir = storyPath.resolve("chat/archive")
        Files.createDirectories(archiveDir)
        val fileName = "turn_%03d_%03d.md".format(fromTurn, toTurn)
        Files.writeString(archiveDir.resolve(fileName), content)
    }

    data class ChatMessage(val role: String, val content: String)

    fun parseMessages(storyPath: Path): List<ChatMessage> {
        val raw = readChatLatest(storyPath)
        val msgs = mutableListOf<ChatMessage>()
        val sections = raw.split(Regex("(?m)^## (USER|ASSISTANT)$"))
        // sections[0] = header, then alternating role/content
        var i = 1
        while (i < sections.size - 1) {
            val role = sections[i].trim()
            val content = sections[i + 1].trim()
            if (content.isNotEmpty()) {
                msgs.add(ChatMessage(role.lowercase(), content))
            }
            i += 2
        }
        return msgs
    }

    fun writeMessages(storyPath: Path, messages: List<ChatMessage>) {
        val chatFile = chatLatestPath(storyPath)
        ensureChatFile(chatFile)
        val sb = StringBuilder("# 최근 대화\n")
        for (msg in messages) {
            val roleTag = msg.role.uppercase()
            sb.append("\n## $roleTag\n${msg.content}\n")
        }
        Files.writeString(chatFile, sb.toString())
    }

    fun editMessage(storyPath: Path, messageIndex: Int, newContent: String): List<ChatMessage> {
        val messages = parseMessages(storyPath).toMutableList()
        if (messageIndex < 0 || messageIndex >= messages.size) {
            throw IllegalArgumentException("Invalid message index: $messageIndex")
        }
        messages[messageIndex] = messages[messageIndex].copy(content = newContent)
        writeMessages(storyPath, messages)
        return messages
    }

    fun deleteMessagesFrom(storyPath: Path, messageIndex: Int): List<ChatMessage> {
        val messages = parseMessages(storyPath).toMutableList()
        if (messageIndex < 0 || messageIndex >= messages.size) {
            throw IllegalArgumentException("Invalid message index: $messageIndex")
        }
        val remaining = messages.subList(0, messageIndex).toList()
        writeMessages(storyPath, remaining)
        return remaining
    }

    fun removeLastAssistantMessage(storyPath: Path): String? {
        val messages = parseMessages(storyPath).toMutableList()
        if (messages.isEmpty()) return null
        val lastMsg = messages.last()
        if (lastMsg.role != "assistant") return null
        messages.removeAt(messages.size - 1)
        writeMessages(storyPath, messages)
        return lastMsg.content
    }

    private fun chatLatestPath(storyPath: Path): Path =
        storyPath.resolve("chat/chat_latest.md")

    private fun ensureChatFile(path: Path) {
        if (!Files.exists(path)) {
            Files.createDirectories(path.parent)
            Files.writeString(path, "# 최근 대화\n\n")
        }
    }
}
