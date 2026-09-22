package com.crack.memory.record

import com.crack.chat.flow.AfterTurnEvent
import com.crack.chat.flow.AfterTurnHook
import com.crack.chat.flow.GenerationMode
import com.crack.message.service.TruncateHook
import com.crack.story.repository.StoryRepository
import org.springframework.stereotype.Component

/**
 * 10턴 자동 기록 트리거 (DESIGN.md §7.2). `turnCount - recorded_through >= every-turns`이면 AUTO로 시작한다.
 * 재생성은 턴을 늘리지 않으므로 거른다(실패한 기록을 후보 넘기기마다 다시 돌리지 않게).
 * [MemoryRecordService.trigger]는 기록 행만 만들고 바로 돌아오므로 응답 완료(`done`)를 늦추지 않는다.
 */
@Component
class MemoryRecordAfterTurnHook(
    private val service: MemoryRecordService,
    private val storyRepository: StoryRepository,
    private val properties: MemoryRecordProperties,
) : AfterTurnHook {

    override fun afterTurn(event: AfterTurnEvent) {
        if (!properties.autoEnabled || event.mode == GenerationMode.REGENERATE) return
        if (service.isRunning(event.storyId)) return
        val story = storyRepository.findById(event.storyId).orElse(null) ?: return
        if (event.turnCount - story.recordedThroughTurn >= properties.everyTurns.coerceAtLeast(1)) {
            service.trigger(event.storyId, RecordReason.AUTO)
        }
    }
}

/** 메시지 삭제가 기록 범위를 자르면 기록을 최근 것부터 되돌린다 (DESIGN.md §7.2, D20). 삭제와 같은 트랜잭션에서 돈다. */
@Component
class MemoryRecordTruncateHook(
    private val service: MemoryRecordService,
) : TruncateHook {

    override fun onTruncate(storyId: Long, minTruncatedTurn: Int) = service.onTruncate(storyId, minTruncatedTurn)
}
