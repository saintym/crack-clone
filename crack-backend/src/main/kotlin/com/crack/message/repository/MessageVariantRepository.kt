package com.crack.message.repository

import com.crack.message.entity.MessageVariant
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface MessageVariantRepository : JpaRepository<MessageVariant, Long> {

    fun findByMessageIdOrderByVariantIndexAsc(messageId: Long): List<MessageVariant>

    fun findByMessageIdAndVariantIndex(messageId: Long, variantIndex: Int): MessageVariant?

    fun countByMessageId(messageId: Long): Long

    @Query("SELECT MAX(v.variantIndex) FROM MessageVariant v WHERE v.messageId = :messageId")
    fun findMaxVariantIndex(@Param("messageId") messageId: Long): Int?

    /** 메시지 ID별 후보 개수. 결과 행은 [messageId, count]. */
    @Query("SELECT v.messageId, COUNT(v) FROM MessageVariant v WHERE v.messageId IN :messageIds GROUP BY v.messageId")
    fun countGroupedByMessageId(@Param("messageIds") messageIds: Collection<Long>): List<Array<Any>>

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("DELETE FROM MessageVariant v WHERE v.messageId IN :messageIds")
    fun deleteByMessageIds(@Param("messageIds") messageIds: Collection<Long>): Int
}
