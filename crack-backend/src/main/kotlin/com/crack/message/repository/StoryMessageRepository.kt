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

    @Query(
        """
        SELECT DISTINCT m.turnNo FROM StoryMessage m
        WHERE m.storyId = :storyId
          AND m.turnNo BETWEEN :fromTurn AND :throughTurn
          AND m.editedAt IS NOT NULL
          AND (:since IS NULL OR m.editedAt > :since)
        ORDER BY m.turnNo
        """
    )
    fun findEditedTurns(
        @Param("storyId") storyId: Long,
        @Param("fromTurn") fromTurn: Int,
        @Param("throughTurn") throughTurn: Int,
        @Param("since") since: LocalDateTime?,
    ): List<Int>

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("DELETE FROM StoryMessage m WHERE m.storyId = :storyId AND m.seq >= :seq")
    fun deleteFromSeq(@Param("storyId") storyId: Long, @Param("seq") seq: Int): Int
}
