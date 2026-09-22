package com.crack.message.service

import com.crack.global.exception.BadRequestException
import com.crack.global.exception.NotFoundException
import com.crack.message.dto.MessageView
import com.crack.message.dto.TruncateResult
import com.crack.message.entity.MessageKind
import com.crack.message.entity.MessageRole
import com.crack.message.entity.MessageVariant
import com.crack.message.entity.StoryMessage
import com.crack.message.repository.MessageVariantRepository
import com.crack.message.repository.StoryMessageRepository
import com.crack.story.entity.Story
import com.crack.story.repository.StoryRepository
import jakarta.persistence.EntityManager
import jakarta.persistence.LockModeType
import org.springframework.beans.factory.ObjectProvider
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime

/**
 * 대화 메시지 저장소 (DESIGN.md §3, §5.1).
 *
 * **턴 규칙**
 * - 유저 메시지는 새 턴을 연다(`turn_no = 현재 최대 turn_no + 1`). 그에 대한 응답은 같은 턴이다.
 * - CONTINUATION 응답은 유저 메시지 없이 새 턴을 연다.
 * - PROLOGUE는 turn 0, seq 0이다.
 * - `stories.turn_count`는 남은 메시지의 최대 turn_no이고, 쓰기마다 다시 계산한다.
 *
 * **동시성:** 같은 스토리에 대한 쓰기는 트랜잭션 안에서 스토리 행을 비관적 락
 * (`SELECT … FOR UPDATE`)으로 잡은 뒤 진행한다. 그래서 seq·turn 계산이 직렬화된다.
 */
@Service
@Transactional(readOnly = true)
class MessageService(
    private val messageRepository: StoryMessageRepository,
    private val variantRepository: MessageVariantRepository,
    private val storyRepository: StoryRepository,
    private val entityManager: EntityManager,
    private val truncateHooks: ObjectProvider<TruncateHook>,
) {

    fun list(storyId: Long): List<MessageView> {
        requireStory(storyId)
        val messages = messageRepository.findByStoryIdOrderBySeqAsc(storyId)
        val counts = variantCounts(messages)
        return messages.map { MessageView.of(it, counts[it.id] ?: 0) }
    }

    fun view(message: StoryMessage): MessageView =
        MessageView.of(message, variantRepository.countByMessageId(message.id).toInt())

    @Transactional
    fun appendUser(storyId: Long, content: String, kind: MessageKind = MessageKind.NORMAL): StoryMessage {
        if (kind != MessageKind.NORMAL && kind != MessageKind.COMMAND) {
            throw BadRequestException("유저 메시지 종류는 NORMAL 또는 COMMAND만 가능합니다: $kind")
        }
        val story = lockStory(storyId)
        val turnNo = currentMaxTurn(storyId) + 1
        val message = messageRepository.save(
            StoryMessage(
                storyId = storyId,
                seq = nextSeq(storyId),
                turnNo = turnNo,
                role = MessageRole.USER,
                kind = kind,
                content = content,
            )
        )
        updateTurnCount(story, turnNo)
        return message
    }

    /**
     * ASSISTANT 메시지를 저장하고 후보 0번을 함께 만든다.
     *
     * - PROLOGUE: turn 0. 스토리에 메시지가 하나도 없을 때만 허용한다(seq 0).
     * - CONTINUATION: 새 턴을 연다. [turnNo]는 무시한다.
     * - 그 외: [turnNo]가 있으면 그 턴(현재 최대 턴 이상이어야 한다), 없으면 현재 최대 턴.
     *   메시지가 없거나 프롤로그뿐이면 턴 1을 연다.
     */
    @Transactional
    fun appendAssistant(
        storyId: Long,
        content: String,
        emotion: String? = null,
        kind: MessageKind = MessageKind.NORMAL,
        turnNo: Int? = null,
    ): StoryMessage {
        val story = lockStory(storyId)
        val maxTurn = currentMaxTurn(storyId)
        val seq = nextSeq(storyId)
        val resolvedTurn = when (kind) {
            MessageKind.PROLOGUE -> {
                if (seq != 0) throw BadRequestException("프롤로그는 스토리의 첫 메시지로만 저장할 수 있습니다")
                0
            }
            MessageKind.CONTINUATION -> maxTurn + 1
            else -> when {
                turnNo == null -> maxOf(maxTurn, 1)
                turnNo < maxTurn || turnNo < 1 ->
                    throw BadRequestException("응답 턴 번호가 올바르지 않습니다: $turnNo (현재 최대 턴 $maxTurn)")
                else -> turnNo
            }
        }
        val message = messageRepository.save(
            StoryMessage(
                storyId = storyId,
                seq = seq,
                turnNo = resolvedTurn,
                role = MessageRole.ASSISTANT,
                kind = kind,
                content = content,
                emotion = emotion,
                selectedVariant = 0,
            )
        )
        variantRepository.save(
            MessageVariant(messageId = message.id, variantIndex = 0, content = content, emotion = emotion)
        )
        updateTurnCount(story, maxOf(maxTurn, resolvedTurn))
        return message
    }

    /** 새 후보를 추가하고 선택한다. 가장 최근 ASSISTANT 메시지만 허용한다(D17). */
    @Transactional
    fun addVariant(messageId: Long, content: String, emotion: String?, instruction: String?): StoryMessage {
        val message = lockAndGetMessage(messageId)
        requireLatestAssistant(message)
        val nextIndex = (variantRepository.findMaxVariantIndex(message.id) ?: -1) + 1
        variantRepository.save(
            MessageVariant(
                messageId = message.id,
                variantIndex = nextIndex,
                content = content,
                emotion = emotion,
                instruction = instruction,
            )
        )
        message.selectedVariant = nextIndex
        message.content = content
        message.emotion = emotion
        return messageRepository.save(message)
    }

    /** 후보를 선택하고 메시지의 content·emotion을 그 후보로 동기화한다. 가장 최근 ASSISTANT만 허용한다. */
    @Transactional
    fun selectVariant(messageId: Long, index: Int): StoryMessage {
        val message = lockAndGetMessage(messageId)
        requireLatestAssistant(message)
        val variant = variantRepository.findByMessageIdAndVariantIndex(message.id, index)
            ?: throw BadRequestException("존재하지 않는 후보입니다: $index")
        message.selectedVariant = index
        message.content = variant.content
        message.emotion = variant.emotion
        return messageRepository.save(message)
    }

    /** 역할과 무관하게 내용을 고치고 edited_at을 기록한다. ASSISTANT면 선택된 후보 내용도 바꾼다. */
    @Transactional
    fun edit(messageId: Long, content: String): StoryMessage {
        val message = lockAndGetMessage(messageId)
        message.content = content
        message.editedAt = LocalDateTime.now()
        if (message.role == MessageRole.ASSISTANT) {
            message.selectedVariant?.let { index ->
                variantRepository.findByMessageIdAndVariantIndex(message.id, index)?.let { variant ->
                    variant.content = content
                    variantRepository.save(variant)
                }
            }
        }
        return messageRepository.save(message)
    }

    /**
     * 해당 메시지와 그 뒤를 전부 삭제한다. turn_count를 다시 계산하고 [TruncateHook] 빈 전부에 알린다.
     */
    @Transactional
    fun truncateFrom(messageId: Long): TruncateResult {
        val target = lockAndGetMessage(messageId)
        val storyId = target.storyId
        val removed = messageRepository.findByStoryIdAndSeqGreaterThanEqualOrderBySeqAsc(storyId, target.seq)
        val minTruncatedTurn = removed.minOf { it.turnNo }

        variantRepository.deleteByMessageIds(removed.map { it.id })
        val deletedCount = messageRepository.deleteFromSeq(storyId, target.seq)

        // 벌크 삭제가 영속성 컨텍스트를 비웠으므로 스토리를 다시 읽는다(락은 트랜잭션 끝까지 유지된다).
        val story = requireStory(storyId)
        val turnCount = currentMaxTurn(storyId)
        updateTurnCount(story, turnCount)

        truncateHooks.orderedStream().forEach { it.onTruncate(storyId, minTruncatedTurn) }
        return TruncateResult(storyId, minTruncatedTurn, deletedCount, turnCount)
    }

    fun turnsInRange(storyId: Long, fromTurn: Int, toTurn: Int): List<StoryMessage> =
        messageRepository.findByStoryIdAndTurnNoBetweenOrderBySeqAsc(storyId, fromTurn, toTurn)

    /**
     * `turn_no <= throughTurn`이면서 [since] 이후(초과) 수정된 턴 번호 목록(오름차순, 중복 없음).
     * [since]가 null이면 수정된 적 있는 모든 턴. 프롤로그(턴 0)는 기억 기록 대상이 아니므로 제외한다.
     */
    fun editedTurnsSince(storyId: Long, throughTurn: Int, since: LocalDateTime?): List<Int> {
        if (throughTurn < 1) return emptyList()
        return messageRepository.findEditedTurns(storyId, 1, throughTurn, since)
    }

    // ---- 내부 ----

    private fun requireStory(storyId: Long): Story =
        storyRepository.findById(storyId).orElseThrow { NotFoundException("스토리를 찾을 수 없습니다: $storyId") }

    private fun lockStory(storyId: Long): Story =
        entityManager.find(Story::class.java, storyId, LockModeType.PESSIMISTIC_WRITE)
            ?: throw NotFoundException("스토리를 찾을 수 없습니다: $storyId")

    /** 메시지의 스토리 행을 잠근 뒤 메시지를 다시 읽는다(락 대기 중 삭제됐을 수 있다). */
    private fun lockAndGetMessage(messageId: Long): StoryMessage {
        val storyId = messageRepository.findStoryIdById(messageId)
            ?: throw NotFoundException("메시지를 찾을 수 없습니다: $messageId")
        lockStory(storyId)
        // 락을 잡은 뒤 쿼리로 다시 확인한다. 락 대기 중 다른 트랜잭션이 지웠을 수 있다.
        return messageRepository.findByIdAndStoryId(messageId, storyId)
            ?: throw NotFoundException("메시지를 찾을 수 없습니다: $messageId")
    }

    private fun requireLatestAssistant(message: StoryMessage) {
        if (message.role != MessageRole.ASSISTANT) {
            throw BadRequestException("ASSISTANT 메시지만 후보를 가질 수 있습니다: ${message.id}")
        }
        val latest = messageRepository.findFirstByStoryIdAndRoleOrderBySeqDesc(message.storyId, MessageRole.ASSISTANT)
        if (latest?.id != message.id) {
            throw BadRequestException("가장 최근 응답만 재생성하거나 후보를 선택할 수 있습니다: ${message.id}")
        }
    }

    private fun currentMaxTurn(storyId: Long): Int = messageRepository.findMaxTurnNo(storyId) ?: 0

    private fun nextSeq(storyId: Long): Int =
        (messageRepository.findFirstByStoryIdOrderBySeqDesc(storyId)?.seq ?: -1) + 1

    private fun updateTurnCount(story: Story, turnCount: Int) {
        if (story.turnCount != turnCount) {
            story.turnCount = turnCount
            storyRepository.save(story)
        }
    }

    private fun variantCounts(messages: List<StoryMessage>): Map<Long, Int> {
        val ids = messages.filter { it.role == MessageRole.ASSISTANT }.map { it.id }
        if (ids.isEmpty()) return emptyMap()
        return variantRepository.countGroupedByMessageId(ids)
            .associate { row -> (row[0] as Number).toLong() to (row[1] as Number).toInt() }
    }
}
