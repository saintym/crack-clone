package com.crack.message

import com.crack.global.exception.BadRequestException
import com.crack.global.exception.NotFoundException
import com.crack.message.dto.ResponseTags
import com.crack.message.entity.MessageKind
import com.crack.message.entity.MessageRole
import com.crack.message.repository.MessageVariantRepository
import com.crack.message.repository.StoryMessageRepository
import com.crack.message.service.MessageService
import com.crack.story.repository.StoryRepository
import jakarta.persistence.EntityManager
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime

@SpringBootTest
@ActiveProfiles("test")
@Transactional
@Import(MessageTestConfig::class)
class MessageServiceTest {

    @Autowired lateinit var messageService: MessageService
    @Autowired lateinit var messageRepository: StoryMessageRepository
    @Autowired lateinit var variantRepository: MessageVariantRepository
    @Autowired lateinit var storyRepository: StoryRepository
    @Autowired lateinit var entityManager: EntityManager

    @Autowired @Qualifier("recordingTruncateHook")
    lateinit var hook: RecordingTruncateHook

    @Autowired @Qualifier("secondTruncateHook")
    lateinit var secondHook: RecordingTruncateHook

    private var storyId: Long = 0

    @BeforeEach
    fun setUp() {
        hook.calls.clear()
        secondHook.calls.clear()
        storyId = storyRepository.createTestStory().id
    }

    private fun turnCount(): Int {
        entityManager.flush()
        entityManager.clear()
        return storyRepository.findById(storyId).get().turnCount
    }

    private fun seqs(): List<Int> = messageRepository.findByStoryIdOrderBySeqAsc(storyId).map { it.seq }

    @Nested
    inner class 턴_번호_규칙 {

        @Test
        fun `유저 메시지는 새 턴을 열고 응답은 같은 턴이다`() {
            val u1 = messageService.appendUser(storyId, "안녕")
            val a1 = messageService.appendAssistant(storyId, "반갑소", ResponseTags(emotion = "경계심"))
            val u2 = messageService.appendUser(storyId, "이름이 뭐요")
            val a2 = messageService.appendAssistant(storyId, "설월이오", turnNo = u2.turnNo)

            assertEquals(listOf(1, 1, 2, 2), listOf(u1, a1, u2, a2).map { it.turnNo })
            assertEquals(listOf(0, 1, 2, 3), listOf(u1, a1, u2, a2).map { it.seq })
            assertEquals(MessageRole.USER, u1.role)
            assertEquals(MessageRole.ASSISTANT, a1.role)
            assertEquals(2, turnCount())
        }

        @Test
        fun `프롤로그는 턴 0, seq 0이고 턴 수에 포함되지 않는다`() {
            val prologue = messageService.appendAssistant(storyId, "눈 내리는 밤이었다", kind = MessageKind.PROLOGUE)
            assertEquals(0, prologue.turnNo)
            assertEquals(0, prologue.seq)
            assertEquals(MessageKind.PROLOGUE, prologue.kind)
            assertEquals(0, turnCount())

            val u1 = messageService.appendUser(storyId, "문을 연다")
            assertEquals(1, u1.turnNo)
            assertEquals(1, u1.seq)
            assertEquals(1, turnCount())
        }

        @Test
        fun `프롤로그는 첫 메시지로만 저장할 수 있다`() {
            messageService.appendUser(storyId, "안녕")
            assertThrows<BadRequestException> {
                messageService.appendAssistant(storyId, "프롤로그", kind = MessageKind.PROLOGUE)
            }
        }

        @Test
        fun `이어쓰기는 유저 메시지 없이 새 턴을 연다`() {
            messageService.appendUser(storyId, "안녕")
            messageService.appendAssistant(storyId, "반갑소")
            val cont = messageService.appendAssistant(storyId, "그때 문이 열렸다", kind = MessageKind.CONTINUATION)
            assertEquals(2, cont.turnNo)
            assertEquals(MessageKind.CONTINUATION, cont.kind)
            assertEquals(2, turnCount())

            val u = messageService.appendUser(storyId, "누구냐")
            assertEquals(3, u.turnNo)
        }

        @Test
        fun `명령 유저 메시지는 COMMAND로 저장되고 새 턴을 연다`() {
            val u = messageService.appendUser(storyId, "/일기 오늘은", MessageKind.COMMAND)
            assertEquals(MessageKind.COMMAND, u.kind)
            assertEquals(1, u.turnNo)
        }

        @Test
        fun `유저 메시지에 PROLOGUE나 CONTINUATION 종류는 쓸 수 없다`() {
            assertThrows<BadRequestException> { messageService.appendUser(storyId, "x", MessageKind.PROLOGUE) }
            assertThrows<BadRequestException> { messageService.appendUser(storyId, "x", MessageKind.CONTINUATION) }
        }

        @Test
        fun `응답 턴 번호가 현재 최대 턴보다 작으면 거부한다`() {
            messageService.appendUser(storyId, "1")
            messageService.appendUser(storyId, "2")
            assertThrows<BadRequestException> { messageService.appendAssistant(storyId, "x", turnNo = 1) }
        }

        @Test
        fun `없는 스토리는 NotFoundException`() {
            assertThrows<NotFoundException> { messageService.appendUser(999_999L, "x") }
            assertThrows<NotFoundException> { messageService.list(999_999L) }
        }
    }

    @Nested
    inner class 후보 {

        @Test
        fun `첫 응답을 저장하면 후보 0번이 함께 생긴다`() {
            messageService.appendUser(storyId, "안녕")
            val a = messageService.appendAssistant(storyId, "반갑소", ResponseTags(emotion = "경계심"))

            assertEquals(0, a.selectedVariant)
            assertEquals("경계심", a.emotion)
            val variants = variantRepository.findByMessageIdOrderByVariantIndexAsc(a.id)
            assertEquals(1, variants.size)
            assertEquals(0, variants[0].variantIndex)
            assertEquals("반갑소", variants[0].content)
            assertEquals("경계심", variants[0].emotion)
        }

        @Test
        fun `후보를 추가하면 선택되고 content와 emotion이 동기화된다`() {
            messageService.appendUser(storyId, "안녕")
            val a = messageService.appendAssistant(storyId, "반갑소", ResponseTags(emotion = "경계심"))

            val updated = messageService.addVariant(a.id, "누구시오", ResponseTags("의심"), "더 차갑게")
            assertEquals(1, updated.selectedVariant)
            assertEquals("누구시오", updated.content)
            assertEquals("의심", updated.emotion)
            val v1 = variantRepository.findByMessageIdAndVariantIndex(a.id, 1)!!
            assertEquals("더 차갑게", v1.instruction)

            val view = messageService.list(storyId).last()
            assertEquals(1, view.variantIndex)
            assertEquals(2, view.variantCount)
            assertEquals("누구시오", view.content)
        }

        @Test
        fun `후보를 선택하면 content와 emotion이 그 후보로 바뀐다`() {
            messageService.appendUser(storyId, "안녕")
            val a = messageService.appendAssistant(storyId, "반갑소", ResponseTags(emotion = "경계심"))
            messageService.addVariant(a.id, "누구시오", ResponseTags("의심"), null)

            val selected = messageService.selectVariant(a.id, 0)
            assertEquals(0, selected.selectedVariant)
            assertEquals("반갑소", selected.content)
            assertEquals("경계심", selected.emotion)
        }

        @Test
        fun `없는 후보 번호는 거부한다`() {
            messageService.appendUser(storyId, "안녕")
            val a = messageService.appendAssistant(storyId, "반갑소")
            assertThrows<BadRequestException> { messageService.selectVariant(a.id, 5) }
        }

        @Test
        fun `과거 ASSISTANT 메시지의 후보 선택과 추가는 거부한다`() {
            messageService.appendUser(storyId, "1")
            val old = messageService.appendAssistant(storyId, "첫 응답")
            messageService.appendUser(storyId, "2")
            messageService.appendAssistant(storyId, "둘째 응답")

            assertThrows<BadRequestException> { messageService.selectVariant(old.id, 0) }
            assertThrows<BadRequestException> { messageService.addVariant(old.id, "새 후보", ResponseTags.NONE, null) }
        }

        @Test
        fun `유저 메시지의 후보 선택은 거부한다`() {
            val u = messageService.appendUser(storyId, "1")
            assertThrows<BadRequestException> { messageService.selectVariant(u.id, 0) }
        }
    }

    @Nested
    inner class 수정 {

        @Test
        fun `유저 메시지를 수정하면 content와 edited_at이 바뀐다`() {
            val u = messageService.appendUser(storyId, "안녕")
            val edited = messageService.edit(u.id, "안녕하시오")
            assertEquals("안녕하시오", edited.content)
            assertNotNull(edited.editedAt)
            assertNull(edited.selectedVariant)
            assertTrue(messageService.list(storyId).first().edited)
        }

        @Test
        fun `ASSISTANT 메시지를 수정하면 선택된 후보 내용도 바뀐다`() {
            messageService.appendUser(storyId, "안녕")
            val a = messageService.appendAssistant(storyId, "반갑소", ResponseTags(emotion = "경계심"))
            messageService.addVariant(a.id, "누구시오", ResponseTags("의심"), null)

            val edited = messageService.edit(a.id, "누구시오, 낯선 이여")
            assertEquals("누구시오, 낯선 이여", edited.content)
            assertNotNull(edited.editedAt)
            assertEquals("의심", edited.emotion)
            assertEquals("누구시오, 낯선 이여", variantRepository.findByMessageIdAndVariantIndex(a.id, 1)!!.content)
            assertEquals("반갑소", variantRepository.findByMessageIdAndVariantIndex(a.id, 0)!!.content)
        }

        @Test
        fun `과거 메시지도 수정할 수 있다`() {
            messageService.appendUser(storyId, "1")
            val old = messageService.appendAssistant(storyId, "첫 응답")
            messageService.appendUser(storyId, "2")
            val edited = messageService.edit(old.id, "고친 첫 응답")
            assertEquals("고친 첫 응답", edited.content)
        }

        @Test
        fun `없는 메시지는 NotFoundException`() {
            assertThrows<NotFoundException> { messageService.edit(999_999L, "x") }
        }
    }

    @Nested
    inner class 잘라내기 {

        @Test
        fun `해당 메시지부터 끝까지 지우고 seq와 turnCount를 유지하며 훅에 알린다`() {
            messageService.appendAssistant(storyId, "프롤로그", kind = MessageKind.PROLOGUE)
            messageService.appendUser(storyId, "1")
            messageService.appendAssistant(storyId, "응답1")
            val u2 = messageService.appendUser(storyId, "2")
            val a2 = messageService.appendAssistant(storyId, "응답2")
            messageService.addVariant(a2.id, "응답2-b", ResponseTags.NONE, null)
            messageService.appendUser(storyId, "3")
            assertEquals(3, turnCount())

            val result = messageService.truncateFrom(u2.id)

            assertEquals(storyId, result.storyId)
            assertEquals(2, result.minTruncatedTurn)
            assertEquals(3, result.deletedCount)
            assertEquals(1, result.turnCount)
            assertEquals(listOf(0, 1, 2), seqs())
            assertEquals(1, turnCount())
            assertTrue(variantRepository.findByMessageIdOrderByVariantIndexAsc(a2.id).isEmpty())
            assertEquals(listOf(storyId to 2), hook.calls)
            assertEquals(listOf(storyId to 2), secondHook.calls)

            // 이어서 추가하면 seq는 연속되고 턴은 다시 2부터
            val u = messageService.appendUser(storyId, "새 2")
            assertEquals(3, u.seq)
            assertEquals(2, u.turnNo)
        }

        @Test
        fun `응답만 잘라내면 같은 턴이 남아 턴 수가 유지된다`() {
            messageService.appendUser(storyId, "1")
            val a1 = messageService.appendAssistant(storyId, "응답1")

            val result = messageService.truncateFrom(a1.id)
            assertEquals(1, result.minTruncatedTurn)
            assertEquals(1, result.turnCount)
            assertEquals(listOf(0), seqs())
            assertEquals(1, turnCount())
            assertEquals(listOf(storyId to 1), hook.calls)
        }

        @Test
        fun `처음부터 잘라내면 턴 수는 0이다`() {
            val u1 = messageService.appendUser(storyId, "1")
            messageService.appendAssistant(storyId, "응답1")

            val result = messageService.truncateFrom(u1.id)
            assertEquals(2, result.deletedCount)
            assertEquals(0, result.turnCount)
            assertTrue(seqs().isEmpty())
            assertEquals(0, turnCount())
        }

        @Test
        fun `다른 스토리의 메시지는 건드리지 않는다`() {
            val otherId = storyRepository.createTestStory("다른 스토리").id
            messageService.appendUser(otherId, "다른 1")
            messageService.appendAssistant(otherId, "다른 응답")
            val u1 = messageService.appendUser(storyId, "1")

            messageService.truncateFrom(u1.id)
            assertEquals(2, messageRepository.findByStoryIdOrderBySeqAsc(otherId).size)
        }
    }

    @Nested
    inner class 조회 {

        @Test
        fun `list는 seq 순서의 MessageView를 돌려준다`() {
            messageService.appendAssistant(storyId, "프롤로그", kind = MessageKind.PROLOGUE)
            messageService.appendUser(storyId, "1")
            messageService.appendAssistant(storyId, "응답1", ResponseTags(emotion = "기쁨"))

            val views = messageService.list(storyId)
            assertEquals(listOf(0, 1, 2), views.map { it.seq })
            assertEquals(listOf(0, 1, 1), views.map { it.turn })
            assertEquals(listOf(MessageRole.ASSISTANT, MessageRole.USER, MessageRole.ASSISTANT), views.map { it.role })
            assertNull(views[1].variantIndex)
            assertEquals(0, views[1].variantCount)
            assertEquals(1, views[2].variantCount)
            assertFalse(views[2].edited)
        }

        @Test
        fun `turnsInRange는 범위 안 턴의 메시지를 seq 순서로 돌려준다`() {
            messageService.appendAssistant(storyId, "프롤로그", kind = MessageKind.PROLOGUE)
            repeat(4) { i ->
                messageService.appendUser(storyId, "u${i + 1}")
                messageService.appendAssistant(storyId, "a${i + 1}")
            }
            val range = messageService.turnsInRange(storyId, 2, 3)
            assertEquals(listOf("u2", "a2", "u3", "a3"), range.map { it.content })
        }

        @Test
        fun `editedTurnsSince는 기준 턴 이하에서 since 이후 수정된 턴만 돌려준다`() {
            messageService.appendAssistant(storyId, "프롤로그", kind = MessageKind.PROLOGUE)
            repeat(5) { i ->
                messageService.appendUser(storyId, "u${i + 1}")
                messageService.appendAssistant(storyId, "a${i + 1}")
            }
            val base = LocalDateTime.of(2026, 9, 1, 12, 0)
            val byContent = messageRepository.findByStoryIdOrderBySeqAsc(storyId).associateBy { it.content }
            fun markEdited(content: String, at: LocalDateTime) {
                byContent.getValue(content).editedAt = at
            }
            markEdited("프롤로그", base.plusHours(1))   // 턴 0: 기록 대상 아님
            markEdited("u1", base.minusHours(1))       // since 이전
            markEdited("a2", base.plusHours(1))
            markEdited("u2", base.plusHours(2))        // 같은 턴 중복
            markEdited("u3", base.plusHours(1))
            markEdited("a5", base.plusHours(1))        // throughTurn 초과
            entityManager.flush()

            assertEquals(listOf(2, 3), messageService.editedTurnsSince(storyId, 4, base))
            assertEquals(listOf(1, 2, 3), messageService.editedTurnsSince(storyId, 4, null))
            assertEquals(listOf(2), messageService.editedTurnsSince(storyId, 2, base))
            assertTrue(messageService.editedTurnsSince(storyId, 0, null).isEmpty())
        }

        @Test
        fun `edit로 수정한 턴이 editedTurnsSince에 잡힌다`() {
            messageService.appendUser(storyId, "u1")
            val a1 = messageService.appendAssistant(storyId, "a1")
            messageService.appendUser(storyId, "u2")
            val before = LocalDateTime.now().minusSeconds(1)

            messageService.edit(a1.id, "a1 수정")
            assertEquals(listOf(1), messageService.editedTurnsSince(storyId, 2, before))
        }
    }
}
