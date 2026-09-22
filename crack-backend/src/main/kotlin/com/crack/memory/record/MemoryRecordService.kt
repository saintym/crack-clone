package com.crack.memory.record

import com.crack.global.exception.BadRequestException
import com.crack.global.exception.NotFoundException
import com.crack.message.entity.MessageRole
import com.crack.message.repository.StoryMessageRepository
import com.crack.message.service.MessageService
import com.crack.story.entity.Story
import com.crack.story.files.StoryDirs
import com.crack.story.repository.StoryRepository
import jakarta.persistence.EntityManager
import jakarta.persistence.LockModeType
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.transaction.support.TransactionTemplate
import java.nio.file.Path
import java.time.LocalDateTime
import java.util.concurrent.ConcurrentHashMap

/** 되돌리기를 막는 실행 중 기록이 있을 때 (409) */
class MemoryRecordConflictException(message: String) : RuntimeException(message)

/** 기록이 반영 전에 취소됐다(삭제 연동 등). 재시도하지 않는다. */
private class RecordCancelledException : RuntimeException("기록이 취소되었습니다")

/** 계산하는 동안 사용자가 문서를 고쳤다. 그 시도를 버리고 다시 계산한다. */
class StaleDocumentsException(message: String) : RuntimeException(message)

/**
 * 기억 기록 파이프라인의 실행·반영·되돌리기 (DESIGN.md §7.2).
 *
 * - **싱글 플라이트:** 스토리당 실행 중 기록은 1개. 프로세스 안 집합([running])과 DB의 RUNNING으로 막는다.
 * - **범위 고정:** 트리거 시점에 `from = recorded_through + 1`, `to = 마지막 완성 턴`, 재반영 턴을 정해 기록 행에 저장한다.
 * - **배경 실행:** 계산([MemoryRecordPipeline])은 전용 실행기에서 돈다. 플레이는 끊기지 않는다(D7).
 * - **원자적 반영:** 스토리 행을 잠근 뒤 변경 검사 → 스냅샷 → 파일 쓰기 → DONE을 한 트랜잭션에서 한다.
 *   트랜잭션이 롤백되면 쓴 파일을 되돌린다. 실패하면 파일은 그대로이고, 1회 재시도 후 FAILED.
 * - **되돌리기와 삭제 연동:** 가장 최근 DONE부터 스택처럼 되돌린다.
 */
@Service
class MemoryRecordService(
    private val recordRepository: MemoryRecordRepository,
    private val storyRepository: StoryRepository,
    private val messageRepository: StoryMessageRepository,
    private val messageService: MessageService,
    private val pipeline: MemoryRecordPipeline,
    private val executors: MemoryRecordExecutors,
    private val properties: MemoryRecordProperties,
    private val storyDirs: StoryDirs,
    private val entityManager: EntityManager,
    transactionManager: PlatformTransactionManager,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val tx = TransactionTemplate(transactionManager)

    /** 이 프로세스에서 기록이 돌고 있는 스토리 */
    private val running: MutableSet<Long> = ConcurrentHashMap.newKeySet()

    // ---- 트리거 ----

    /**
     * 기록을 시작한다. 계산과 반영은 배경에서 돌고, 이 함수는 기록 행만 만들고 바로 돌아온다.
     * 실행 중이면 [TriggerResult.ALREADY_RUNNING], 기록할 턴도 재반영할 턴도 없으면 [TriggerResult.NOTHING_TO_RECORD].
     */
    fun trigger(storyId: Long, reason: RecordReason): TriggerResponse {
        requireStory(storyId)
        if (!running.add(storyId)) return TriggerResponse(TriggerResult.ALREADY_RUNNING, runningSummary(storyId))
        var started = false
        try {
            val (result, record) = tx.execute { createRecord(storyId, reason) }!!
            if (result != TriggerResult.STARTED || record == null) {
                return TriggerResponse(result, record?.let { summary(it) })
            }
            val job = RecordJob.of(record)
            executors.runner.execute {
                try {
                    execute(job)
                } finally {
                    running.remove(storyId)
                }
            }
            started = true
            log.info("기억 기록 시작: storyId={}, recordId={}, 턴 {}–{}, 재반영 {}, {}", storyId, record.id, job.fromTurn, job.toTurn, job.rerecordedTurns, reason)
            return TriggerResponse(TriggerResult.STARTED, summary(record))
        } finally {
            if (!started) running.remove(storyId)
        }
    }

    /** 이 스토리의 기록이 이 프로세스에서 돌고 있는지 */
    fun isRunning(storyId: Long): Boolean = running.contains(storyId)

    private fun createRecord(storyId: Long, reason: RecordReason): Pair<TriggerResult, MemoryRecord?> {
        val story = lockStory(storyId)
        recordRepository.findFirstByStoryIdAndStatusOrderByIdDesc(storyId, RecordStatus.RUNNING)?.let {
            return TriggerResult.ALREADY_RUNNING to it
        }
        val recorded = story.recordedThroughTurn
        val last = messageRepository.findFirstByStoryIdOrderBySeqDesc(storyId)
        // 응답이 없는 마지막 유저 메시지(생성 중이거나 전송 실패)의 턴은 아직 완성되지 않았다
        val to = when {
            last == null -> 0
            last.role == MessageRole.USER -> last.turnNo - 1
            else -> messageRepository.findMaxTurnNo(storyId) ?: 0
        }
        val from = recorded + 1
        val lastDone = recordRepository.findFirstByStoryIdAndStatusOrderByIdDesc(storyId, RecordStatus.DONE)
        val edited = messageService.editedTurnsSince(storyId, recorded, lastDone?.createdAt)
        if (to < from && edited.isEmpty()) return TriggerResult.NOTHING_TO_RECORD to null

        val record = MemoryRecord(
            storyId = storyId,
            fromTurn = from,
            toTurn = maxOf(to, recorded), // 새 턴이 없으면 빈 범위(from - 1)
            reason = reason,
        ).apply { rerecordedTurnList = edited }
        return TriggerResult.STARTED to recordRepository.save(record)
    }

    // ---- 실행 ----

    private fun execute(job: RecordJob) {
        var lastError: Throwable? = null
        val attempts = properties.maxAttempts.coerceAtLeast(1)
        for (attempt in 1..attempts) {
            if (!isStillRunning(job.recordId)) return
            try {
                val plan = pipeline.compute(job)
                apply(job, plan)
                log.info("기억 기록 완료: storyId={}, recordId={}", job.storyId, job.recordId)
                return
            } catch (e: RecordCancelledException) {
                log.info("기억 기록 취소: storyId={}, recordId={}", job.storyId, job.recordId)
                return
            } catch (e: Throwable) {
                lastError = e
                log.warn("기억 기록 실패 (시도 {}/{}): storyId={}, recordId={}: {}", attempt, attempts, job.storyId, job.recordId, e.toString())
            }
        }
        log.error("기억 기록 FAILED: storyId=${job.storyId}, recordId=${job.recordId}", lastError)
        markFailed(job.recordId, lastError?.let { it.message ?: it.javaClass.simpleName } ?: "알 수 없는 오류")
    }

    private fun isStillRunning(recordId: Long): Boolean =
        recordRepository.findById(recordId).map { it.status == RecordStatus.RUNNING }.orElse(false)

    /** ⑤ 원자적 반영. 스토리 잠금 → 취소·변경 검사 → 스냅샷 → 파일 쓰기 → DONE, recorded_through = to */
    private fun apply(job: RecordJob, plan: RecordPlan) {
        tx.executeWithoutResult {
            val story = lockStory(job.storyId)
            val record = recordRepository.findById(job.recordId).orElse(null)
            if (record == null || record.status != RecordStatus.RUNNING) throw RecordCancelledException()

            val dir = plan.storyDir
            plan.inputs.forEach { (rel, content) ->
                if (MemoryRecordFiles.read(dir, rel) != content) throw StaleDocumentsException("기록 중 문서가 바뀌었습니다: $rel")
            }
            val changed = plan.outputs.filter { (rel, content) -> plan.inputs[rel] != content }
            if (changed.isNotEmpty()) {
                val before = changed.keys.associateWith { plan.inputs[it] }
                MemoryRecordFiles.saveSnapshot(dir, record.id, before)
                restoreOnRollback(dir, before)
                MemoryRecordFiles.writeAll(dir, changed, before)
            }
            record.status = RecordStatus.DONE
            record.changedFileList = changed.keys.toList()
            record.error = null
            record.finishedAt = LocalDateTime.now()
            recordRepository.save(record)
            story.recordedThroughTurn = job.toTurn
            storyRepository.save(story)
        }
    }

    private fun markFailed(recordId: Long, error: String) {
        tx.executeWithoutResult {
            val record = recordRepository.findById(recordId).orElse(null) ?: return@executeWithoutResult
            if (record.status != RecordStatus.RUNNING) return@executeWithoutResult
            record.status = RecordStatus.FAILED
            record.error = error.take(MAX_ERROR_LENGTH)
            record.finishedAt = LocalDateTime.now()
            recordRepository.save(record)
        }
    }

    // ---- 되돌리기 ----

    /** 가장 최근 DONE만 되돌린다(아니면 400). 실행 중인 기록이 있으면 409. */
    fun revert(storyId: Long, recordId: Long): MemoryRecordSummary {
        requireStory(storyId)
        if (running.contains(storyId)) throw MemoryRecordConflictException("기억 기록이 진행 중입니다. 끝난 뒤 되돌리세요")
        val record = tx.execute {
            val story = lockStory(storyId)
            if (recordRepository.findFirstByStoryIdAndStatusOrderByIdDesc(storyId, RecordStatus.RUNNING) != null) {
                throw MemoryRecordConflictException("기억 기록이 진행 중입니다. 끝난 뒤 되돌리세요")
            }
            val record = recordRepository.findByIdAndStoryId(recordId, storyId)
                ?: throw NotFoundException("기억 기록을 찾을 수 없습니다: $recordId")
            val latestDone = recordRepository.findFirstByStoryIdAndStatusOrderByIdDesc(storyId, RecordStatus.DONE)
            if (record.status != RecordStatus.DONE || latestDone?.id != record.id) {
                throw BadRequestException("가장 최근에 반영된 기록만 되돌릴 수 있습니다: $recordId")
            }
            try {
                revertInternal(story, record, strict = true)
            } catch (e: MissingSnapshotException) {
                throw BadRequestException("스냅샷이 없어 되돌릴 수 없습니다: ${e.message}")
            }
            record
        }!!
        log.info("기억 기록 되돌림: storyId={}, recordId={}", storyId, recordId)
        return summary(record)
    }

    /**
     * 스냅샷으로 파일을 복원하고 REVERTED, `recorded_through = from - 1`. 호출하는 쪽의 트랜잭션 안에서 돈다.
     * [strict]가 false면(삭제 연동) 스냅샷이 없어도 로그만 남기고 상태와 턴은 내린다(삭제를 막지 않기 위해).
     */
    private fun revertInternal(story: Story, record: MemoryRecord, strict: Boolean) {
        val files = record.changedFileList
        if (files.isNotEmpty()) {
            val dir = storyDirs.locate(story.id).dir
            try {
                val restoreTo = files.associateWith { MemoryRecordFiles.snapshotOf(dir, record.id, it) }
                val current = files.associateWith { MemoryRecordFiles.read(dir, it) }
                restoreOnRollback(dir, current)
                MemoryRecordFiles.restore(dir, restoreTo)
            } catch (e: MissingSnapshotException) {
                if (strict) throw e
                log.error("스냅샷이 없어 파일은 두고 기록 상태만 되돌린다: storyId={}, recordId={}: {}", story.id, record.id, e.message)
            }
        }
        record.status = RecordStatus.REVERTED
        recordRepository.save(record)
        story.recordedThroughTurn = maxOf(0, record.fromTurn - 1)
        storyRepository.save(story)
    }

    /**
     * 메시지 삭제 연동 (DESIGN.md §7.2, D20). 삭제 트랜잭션 안에서 [com.crack.message.service.TruncateHook]이 부른다.
     *
     * 1. 범위가 잘린 턴에 걸친 RUNNING 기록은 FAILED(취소)로 바꾼다. 실행 스레드는 반영 직전 검사에서 멈춘다.
     * 2. `recorded_through < 잘린 턴`이 될 때까지 최근 DONE부터 되돌린다. 되돌릴 DONE이 없으면 턴만 내린다.
     */
    fun onTruncate(storyId: Long, minTruncatedTurn: Int) {
        tx.executeWithoutResult {
            val story = entityManager.find(Story::class.java, storyId, LockModeType.PESSIMISTIC_WRITE) ?: return@executeWithoutResult
            recordRepository.findByStoryIdAndStatus(storyId, RecordStatus.RUNNING)
                .filter { minTruncatedTurn <= it.toTurn }
                .forEach {
                    it.status = RecordStatus.FAILED
                    it.error = "기록 범위의 대화가 삭제되어 취소되었습니다"
                    it.finishedAt = LocalDateTime.now()
                    recordRepository.save(it)
                }

            val floor = maxOf(minTruncatedTurn, 1) // 턴 0(프롤로그)은 기록 대상이 아니다
            while (story.recordedThroughTurn >= floor) {
                val latest = recordRepository.findFirstByStoryIdAndStatusOrderByIdDesc(storyId, RecordStatus.DONE)
                if (latest == null) {
                    log.warn("되돌릴 기록 없이 recorded_through만 내린다: storyId={}, {} → {}", storyId, story.recordedThroughTurn, floor - 1)
                    story.recordedThroughTurn = floor - 1
                    storyRepository.save(story)
                    break
                }
                log.info("대화 삭제로 기억 기록을 되돌린다: storyId={}, recordId={}, 잘린 턴 {}", storyId, latest.id, minTruncatedTurn)
                revertInternal(story, latest, strict = false)
            }
        }
    }

    /** 현재 트랜잭션이 롤백되면 파일을 [contents]로 되돌린다(파일은 트랜잭션에 묶이지 않으므로). */
    private fun restoreOnRollback(dir: Path, contents: Map<String, String?>) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) return
        TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
            override fun afterCompletion(status: Int) {
                if (status != TransactionSynchronization.STATUS_COMMITTED) {
                    try {
                        MemoryRecordFiles.restore(dir, contents)
                    } catch (e: Exception) {
                        log.error("트랜잭션 롤백 후 파일 복원 실패: $dir ${contents.keys}", e)
                    }
                }
            }
        })
    }

    // ---- 조회 ----

    fun list(storyId: Long): List<MemoryRecordSummary> {
        requireStory(storyId)
        val records = recordRepository.findByStoryIdOrderByIdDesc(storyId)
        val revertableId = revertableId(storyId, records)
        return records.map { MemoryRecordSummary.of(it, it.id == revertableId) }
    }

    fun detail(storyId: Long, recordId: Long): MemoryRecordDetail {
        requireStory(storyId)
        val record = recordRepository.findByIdAndStoryId(recordId, storyId)
            ?: throw NotFoundException("기억 기록을 찾을 수 없습니다: $recordId")
        val files = if (record.changedFileList.isEmpty()) {
            emptyList()
        } else {
            val dir = storyDirs.locate(storyId).dir
            record.changedFileList.map { rel ->
                val before = try {
                    MemoryRecordFiles.snapshotOf(dir, record.id, rel)
                } catch (e: MissingSnapshotException) {
                    null
                }
                MemoryRecordFileView(rel, before, MemoryRecordFiles.read(dir, rel))
            }
        }
        return MemoryRecordDetail.of(summary(record), files)
    }

    fun status(storyId: Long): MemoryStatusView {
        val latest = recordRepository.findFirstByStoryIdOrderByIdDesc(storyId) ?: return MemoryStatusView.NONE
        val unseen = recordRepository.existsByStoryIdAndSeenFalseAndStatusIn(storyId, UNSEEN_STATUSES)
        return MemoryStatusView(latest.status.name, latest.id, unseen)
    }

    fun markSeen(storyId: Long) {
        requireStory(storyId)
        tx.executeWithoutResult { recordRepository.markAllSeen(storyId) }
    }

    /** 서버가 기록 도중 꺼졌으면 RUNNING이 남는다. 기동할 때 FAILED로 바꾼다. */
    @EventListener(ApplicationReadyEvent::class)
    fun failStaleRunning() {
        tx.executeWithoutResult {
            recordRepository.findByStatus(RecordStatus.RUNNING)
                .filter { !running.contains(it.storyId) }
                .forEach {
                    it.status = RecordStatus.FAILED
                    it.error = "서버가 다시 시작되어 중단되었습니다"
                    it.finishedAt = LocalDateTime.now()
                    recordRepository.save(it)
                    log.warn("중단된 기억 기록을 FAILED로 바꾼다: storyId={}, recordId={}", it.storyId, it.id)
                }
        }
    }

    // ---- 내부 ----

    private fun summary(record: MemoryRecord): MemoryRecordSummary =
        MemoryRecordSummary.of(record, record.id == revertableId(record.storyId, null))

    private fun revertableId(storyId: Long, records: List<MemoryRecord>?): Long? {
        val all = records ?: recordRepository.findByStoryIdOrderByIdDesc(storyId)
        if (running.contains(storyId) || all.any { it.status == RecordStatus.RUNNING }) return null
        return all.firstOrNull { it.status == RecordStatus.DONE }?.id
    }

    private fun runningSummary(storyId: Long): MemoryRecordSummary? =
        recordRepository.findFirstByStoryIdAndStatusOrderByIdDesc(storyId, RecordStatus.RUNNING)?.let { summary(it) }

    private fun requireStory(storyId: Long): Story =
        storyRepository.findById(storyId).orElseThrow { NotFoundException("스토리를 찾을 수 없습니다: $storyId") }

    private fun lockStory(storyId: Long): Story =
        entityManager.find(Story::class.java, storyId, LockModeType.PESSIMISTIC_WRITE)
            ?: throw NotFoundException("스토리를 찾을 수 없습니다: $storyId")

    companion object {
        private const val MAX_ERROR_LENGTH = 2000
        private val UNSEEN_STATUSES = listOf(RecordStatus.DONE, RecordStatus.FAILED)
    }
}
