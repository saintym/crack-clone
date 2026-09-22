package com.crack.memory.record

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface MemoryRecordRepository : JpaRepository<MemoryRecord, Long> {

    fun findByStoryIdOrderByIdDesc(storyId: Long): List<MemoryRecord>

    fun findFirstByStoryIdOrderByIdDesc(storyId: Long): MemoryRecord?

    fun findFirstByStoryIdAndStatusOrderByIdDesc(storyId: Long, status: RecordStatus): MemoryRecord?

    fun findByStoryIdAndStatus(storyId: Long, status: RecordStatus): List<MemoryRecord>

    fun findByStatus(status: RecordStatus): List<MemoryRecord>

    fun findByIdAndStoryId(id: Long, storyId: Long): MemoryRecord?

    fun existsByStoryIdAndSeenFalseAndStatusIn(storyId: Long, statuses: Collection<RecordStatus>): Boolean

    /** [statuses] 상태인 안 읽은 기록을 읽음 처리한다. 진행 중(RUNNING) 기록은 넘기지 않는다(BUG-009). */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE MemoryRecord r SET r.seen = true WHERE r.storyId = :storyId AND r.seen = false AND r.status IN :statuses")
    fun markSeen(@Param("storyId") storyId: Long, @Param("statuses") statuses: Collection<RecordStatus>): Int
}
