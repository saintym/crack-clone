package com.crack.message.repository

import com.crack.message.entity.MessageRole
import com.crack.message.entity.StoryMessage
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.LocalDateTime

interface StoryMessageRepository : JpaRepository<StoryMessage, Long> {

    @Query("SELECT m.storyId FROM StoryMessage m WHERE m.id = :id")
    fun findStoryIdById(@Param("id") id: Long): Long?

    fun findByIdAndStoryId(id: Long, storyId: Long): StoryMessage?

    fun findByStoryIdOrderBySeqAsc(storyId: Long): List<StoryMessage>

    fun findFirstByStoryIdOrderBySeqDesc(storyId: Long): StoryMessage?

    fun findFirstByStoryIdAndRoleOrderBySeqDesc(storyId: Long, role: MessageRole): StoryMessage?

    fun findByStoryIdAndTurnNoBetweenOrderBySeqAsc(storyId: Long, fromTurn: Int, toTurn: Int): List<StoryMessage>

    fun findByStoryIdAndSeqGreaterThanEqualOrderBySeqAsc(storyId: Long, seq: Int): List<StoryMessage>

    @Query("SELECT MAX(m.turnNo) FROM StoryMessage m WHERE m.storyId = :storyId")
    fun findMaxTurnNo(@Param("storyId") storyId: Long): Int?

    /** 수정된 적 있는 모든 턴. `since` 유무를 한 쿼리에서 `IS NULL`로 분기하면 PostgreSQL이 파라미터 타입을 추론하지 못한다(BUG-008). */
    @Query(
        """
        SELECT DISTINCT m.turnNo FROM StoryMessage m
        WHERE m.storyId = :storyId
          AND m.turnNo BETWEEN :fromTurn AND :throughTurn
          AND m.editedAt IS NOT NULL
        ORDER BY m.turnNo
        """
    )
    fun findEditedTurns(
        @Param("storyId") storyId: Long,
        @Param("fromTurn") fromTurn: Int,
        @Param("throughTurn") throughTurn: Int,
    ): List<Int>

    /** [since] 이후(초과) 수정된 턴. */
    @Query(
        """
        SELECT DISTINCT m.turnNo FROM StoryMessage m
        WHERE m.storyId = :storyId
          AND m.turnNo BETWEEN :fromTurn AND :throughTurn
          AND m.editedAt > :since
        ORDER BY m.turnNo
        """
    )
    fun findEditedTurnsSince(
        @Param("storyId") storyId: Long,
        @Param("fromTurn") fromTurn: Int,
        @Param("throughTurn") throughTurn: Int,
        @Param("since") since: LocalDateTime,
    ): List<Int>

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("DELETE FROM StoryMessage m WHERE m.storyId = :storyId AND m.seq >= :seq")
    fun deleteFromSeq(@Param("storyId") storyId: Long, @Param("seq") seq: Int): Int
}
