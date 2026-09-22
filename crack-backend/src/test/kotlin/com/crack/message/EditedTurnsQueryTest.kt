package com.crack.message

import com.crack.message.repository.MessageVariantRepository
import com.crack.message.repository.StoryMessageRepository
import com.crack.message.service.MessageService
import com.crack.story.repository.StoryRepository
import jakarta.persistence.EntityManager
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.ObjectProvider
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider
import org.springframework.core.type.filter.AssignableTypeFilter
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.Repository
import java.time.LocalDateTime

/**
 * BUG-008 회귀 테스트. H2는 `(:since IS NULL OR m.editedAt > :since)`를 받아 주지만 PostgreSQL은
 * `since`가 null이 아닐 때 파라미터 타입을 추론하지 못한다(42P18). H2로는 재현되지 않으므로
 * ① `since` 유무에 따른 쿼리 분기와 ② 모든 `@Query`에 "파라미터 IS NULL" 패턴이 없는지를 검증한다.
 */
class EditedTurnsQueryTest {

    private val messageRepository: StoryMessageRepository = mock()
    private val messageService = MessageService(
        messageRepository,
        mock<MessageVariantRepository>(),
        mock<StoryRepository>(),
        mock<EntityManager>(),
        mock<ObjectProvider<com.crack.message.service.TruncateHook>>(),
    )

    @Test
    fun `since가 null이면 since 없는 쿼리를 쓴다`() {
        whenever(messageRepository.findEditedTurns(1L, 1, 5)).thenReturn(listOf(2))

        assertEquals(listOf(2), messageService.editedTurnsSince(1L, 5, null))
        verify(messageRepository, never()).findEditedTurnsSince(any(), any(), any(), any())
    }

    @Test
    fun `since가 있으면 since 비교 쿼리를 쓴다`() {
        val since = LocalDateTime.of(2026, 9, 1, 12, 0)
        whenever(messageRepository.findEditedTurnsSince(1L, 1, 5, since)).thenReturn(listOf(3))

        assertEquals(listOf(3), messageService.editedTurnsSince(1L, 5, since))
        verify(messageRepository, never()).findEditedTurns(any(), any(), eq(5))
    }

    @Test
    fun `JPQL에 파라미터 IS NULL 분기가 없다`() {
        val scanner = object : ClassPathScanningCandidateComponentProvider(false) {
            override fun isCandidateComponent(beanDefinition: org.springframework.beans.factory.annotation.AnnotatedBeanDefinition) =
                beanDefinition.metadata.isInterface
        }
        scanner.addIncludeFilter(AssignableTypeFilter(Repository::class.java))
        val repositories = scanner.findCandidateComponents("com.crack").map { Class.forName(it.beanClassName) }
        assertTrue(repositories.contains(StoryMessageRepository::class.java))

        val nullableParam = Regex(""":\w+\s+IS\s+(NOT\s+)?NULL""", RegexOption.IGNORE_CASE)
        val offenders = repositories.flatMap { repo ->
            repo.declaredMethods.mapNotNull { method ->
                val query = method.getAnnotation(Query::class.java)?.value ?: return@mapNotNull null
                if (nullableParam.containsMatchIn(query)) "${repo.simpleName}.${method.name}" else null
            }
        }
        assertTrue(offenders.isEmpty(), "PostgreSQL에서 타입 추론이 실패하는 쿼리: $offenders")
    }
}
