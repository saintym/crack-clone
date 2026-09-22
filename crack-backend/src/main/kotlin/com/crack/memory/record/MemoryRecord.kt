package com.crack.memory.record

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import jakarta.persistence.*
import java.time.LocalDateTime

/** 기록을 시작한 이유 */
enum class RecordReason { AUTO, MANUAL }

/** 기록 상태 (DESIGN.md §3 V7) */
enum class RecordStatus { RUNNING, DONE, FAILED, REVERTED }

/**
 * 기억 기록 한 회 (DESIGN.md §3 V7, §7.2).
 *
 * - 범위 `fromTurn..toTurn`은 트리거 시점에 고정한다. 새 턴 없이 재반영만 하는 기록은 `toTurn = fromTurn - 1`(빈 범위)이다.
 * - [changedFiles], [rerecordedTurns]는 JSON 배열 문자열로 저장한다. 읽고 쓸 때는 [changedFileList], [rerecordedTurnList]를 쓴다.
 */
@Entity
@Table(
    name = "memory_records",
    indexes = [Index(name = "idx_memory_records_story", columnList = "story_id, id")],
)
class MemoryRecord(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(name = "story_id", nullable = false)
    val storyId: Long,

    @Column(name = "from_turn", nullable = false)
    val fromTurn: Int,

    @Column(name = "to_turn", nullable = false)
    val toTurn: Int,

    @Column(nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    val reason: RecordReason,

    @Column(nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    var status: RecordStatus = RecordStatus.RUNNING,

    @Column(name = "changed_files", columnDefinition = "TEXT")
    var changedFiles: String? = null,

    @Column(name = "rerecorded_turns", columnDefinition = "TEXT")
    var rerecordedTurns: String? = null,

    @Column(columnDefinition = "TEXT")
    var error: String? = null,

    /** 뱃지 읽음 표시 */
    @Column(nullable = false)
    var seen: Boolean = false,

    /** 트리거 시각 = 범위를 고정한 시각. 다음 기록의 재반영 기준이다(DESIGN.md §3 턴 규칙). */
    @Column(name = "created_at")
    val createdAt: LocalDateTime = LocalDateTime.now(),

    @Column(name = "finished_at")
    var finishedAt: LocalDateTime? = null,
) {
    var changedFileList: List<String>
        get() = changedFiles?.let { MAPPER.readValue<List<String>>(it) } ?: emptyList()
        set(value) {
            changedFiles = MAPPER.writeValueAsString(value)
        }

    var rerecordedTurnList: List<Int>
        get() = rerecordedTurns?.let { MAPPER.readValue<List<Int>>(it) } ?: emptyList()
        set(value) {
            rerecordedTurns = MAPPER.writeValueAsString(value)
        }

    /** 새로 기록한 턴이 있는지(재반영만 하는 기록이면 false) */
    val hasNewTurns: Boolean get() = toTurn >= fromTurn

    companion object {
        private val MAPPER: ObjectMapper = jacksonObjectMapper()
    }
}
