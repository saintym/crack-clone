package com.crack.story.prologue

import com.crack.message.entity.MessageKind
import com.crack.message.entity.StoryMessage
import com.crack.message.service.MessageService
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.io.IOException
import java.nio.file.Path

/**
 * 새 스토리에 첫 메시지(프롤로그)를 넣는다 (D16).
 *
 * 프롤로그는 `turn_no = 0`, `seq = 0`, `kind = PROLOGUE`, `role = ASSISTANT`다(DESIGN.md §3 턴 규칙).
 * 턴 수(`stories.turn_count`)는 0 그대로다.
 */
@Service
class PrologueService(
    private val messageService: MessageService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 스토리 폴더의 `prologue.md`가 비어 있지 않으면 첫 메시지로 저장하고 돌려준다. 없거나 비었으면 null.
     *
     * 파일을 읽지 못하면 경고만 남기고 건너뛴다. 첫 메시지 때문에 스토리 생성이 실패하지 않게 하려는 것이다.
     * 메시지 저장(DB) 실패는 그대로 던진다(호출한 트랜잭션이 롤백된다).
     */
    @Transactional
    fun insertIfPresent(storyId: Long, storyDir: Path): StoryMessage? {
        val content = try {
            Prologue.read(storyDir)
        } catch (e: IOException) {
            log.warn("첫 메시지 파일을 읽지 못해 건너뛴다. storyId={}, dir={}", storyId, storyDir, e)
            null
        } ?: return null

        return messageService.appendAssistant(storyId, content, kind = MessageKind.PROLOGUE, turnNo = 0)
    }
}
