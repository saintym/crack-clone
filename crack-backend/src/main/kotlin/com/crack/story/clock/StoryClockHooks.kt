package com.crack.story.clock

import com.crack.message.repository.StoryMessageRepository
import com.crack.message.service.TruncateHook
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * 메시지를 지우거나 자르면 시계도 그 시점으로 되돌린다 (T38, DESIGN.md §5.4).
 *
 * 남은 메시지 중 시각이 적힌 마지막 것을 찾아 거기로 맞춘다. 하나도 남지 않으면 `state.json`의 값을 지워
 * 시작 시각(`settings.json`의 `clock.start`)으로 돌아가게 한다.
 *
 * 삭제와 같은 트랜잭션에서 돌지만 **예외를 던지지 않는다.** 시계를 되돌리지 못한 것 때문에
 * 사용자가 요청한 삭제를 롤백하지는 않는다(파일 쓰기는 어차피 롤백되지 않는다).
 */
@Component
class StoryClockTruncateHook(
    private val clockService: StoryClockService,
    private val messageRepository: StoryMessageRepository,
) : TruncateHook {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun onTruncate(storyId: Long, minTruncatedTurn: Int) {
        try {
            val last = messageRepository.findFirstByStoryIdAndStoryTimeIsNotNullOrderBySeqDesc(storyId)
            val point = last?.storyTime?.let { ClockPoint(it, last.place) }
            clockService.moveTo(storyId, point)
        } catch (e: Exception) {
            log.warn("삭제 뒤 시계를 되돌리지 못했다: storyId={} ({})", storyId, e.message)
        }
    }
}
