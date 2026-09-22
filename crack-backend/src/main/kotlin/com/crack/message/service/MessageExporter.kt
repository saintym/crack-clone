package com.crack.message.service

import com.crack.global.exception.NotFoundException
import com.crack.message.repository.StoryMessageRepository
import com.crack.story.repository.StoryRepository
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

/**
 * 대화를 마크다운으로 내보낸다(`GET /messages/export`, T07에서 연결).
 *
 * ```
 * # {스토리 제목}
 *
 * ## 턴 0 · ASSISTANT
 *
 * (프롤로그)
 *
 * ## 턴 1 · USER
 *
 * ...
 * ```
 * 감정 값은 내보내지 않는다(DESIGN.md §5.3).
 */
@Component
class MessageExporter(
    private val messageRepository: StoryMessageRepository,
    private val storyRepository: StoryRepository,
) {

    @Transactional(readOnly = true)
    fun export(storyId: Long): String {
        val story = storyRepository.findById(storyId)
            .orElseThrow { NotFoundException("스토리를 찾을 수 없습니다: $storyId") }
        val messages = messageRepository.findByStoryIdOrderBySeqAsc(storyId)
        return buildString {
            append("# ").append(story.title).append("\n")
            for (m in messages) {
                append("\n## 턴 ").append(m.turnNo).append(" · ").append(m.role.name).append("\n\n")
                append(m.content.trimEnd()).append("\n")
            }
        }
    }
}
