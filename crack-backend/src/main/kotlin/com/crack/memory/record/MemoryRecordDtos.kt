package com.crack.memory.record

import java.time.LocalDateTime

/** `POST /memory/record` 결과 종류 */
enum class TriggerResult { STARTED, ALREADY_RUNNING, NOTHING_TO_RECORD }

/** `POST /memory/record` 응답. [record]는 시작했거나 실행 중인 기록(없으면 null). */
data class TriggerResponse(val result: TriggerResult, val record: MemoryRecordSummary?)

/** 기록 목록 항목 (DESIGN.md §7.2 `MemoryRecordSummary`) */
data class MemoryRecordSummary(
    val id: Long,
    val fromTurn: Int,
    val toTurn: Int,
    val reason: RecordReason,
    val status: RecordStatus,
    val changedFiles: List<String>,
    val rerecordedTurns: List<Int>,
    val error: String?,
    val createdAt: LocalDateTime,
    val finishedAt: LocalDateTime?,
    /** 가장 최근 DONE이고 실행 중인 기록이 없을 때 true */
    val revertable: Boolean,
) {
    companion object {
        fun of(record: MemoryRecord, revertable: Boolean) = MemoryRecordSummary(
            id = record.id,
            fromTurn = record.fromTurn,
            toTurn = record.toTurn,
            reason = record.reason,
            status = record.status,
            changedFiles = record.changedFileList,
            rerecordedTurns = record.rerecordedTurnList,
            error = record.error,
            createdAt = record.createdAt,
            finishedAt = record.finishedAt,
            revertable = revertable,
        )
    }
}

/**
 * 변경 파일 하나. diff는 프론트가 계산한다.
 * @property before 기록 직전 내용. 기록 전에 없던 파일이거나 스냅샷이 없으면 null
 * @property current 지금 내용. 파일이 없으면 null
 */
data class MemoryRecordFileView(val path: String, val before: String?, val current: String?)

/** `GET /memory/records/{id}` 응답: [MemoryRecordSummary]의 필드 + [files] */
data class MemoryRecordDetail(
    val id: Long,
    val fromTurn: Int,
    val toTurn: Int,
    val reason: RecordReason,
    val status: RecordStatus,
    val changedFiles: List<String>,
    val rerecordedTurns: List<Int>,
    val error: String?,
    val createdAt: LocalDateTime,
    val finishedAt: LocalDateTime?,
    val revertable: Boolean,
    val files: List<MemoryRecordFileView>,
) {
    companion object {
        fun of(s: MemoryRecordSummary, files: List<MemoryRecordFileView>) = MemoryRecordDetail(
            s.id, s.fromTurn, s.toTurn, s.reason, s.status, s.changedFiles, s.rerecordedTurns,
            s.error, s.createdAt, s.finishedAt, s.revertable, files,
        )
    }
}

/**
 * `GET /messages`의 `story.memory` (DESIGN.md §7.2).
 * @property status 가장 최근 기록의 상태. 기록이 없으면 `NONE`
 * @property unseen 읽음 처리되지 않은 DONE 또는 FAILED 기록이 있으면 true
 */
data class MemoryStatusView(val status: String, val lastRecordId: Long?, val unseen: Boolean) {
    companion object {
        const val NO_RECORD = "NONE"
        val NONE = MemoryStatusView(NO_RECORD, null, false)
    }
}
