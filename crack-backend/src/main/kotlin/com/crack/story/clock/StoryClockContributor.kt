package com.crack.story.clock

import com.crack.prompt.contributor.PromptContext
import com.crack.prompt.contributor.PromptContributor
import com.crack.prompt.contributor.PromptSlot
import org.springframework.stereotype.Component

/**
 * BOTTOM(10): 이야기 속 현재 시각과 장소 (T38, DESIGN.md §5.4, D39). 섹션 이름은 `story_clock`.
 *
 * 값만 넣는다. 태그를 어떻게 쓰라는 규칙은 BASE 출력 형식에 있다
 * ([com.crack.prompt.contributor.BaseContributor.CLOCK_RULES]).
 *
 * **BOTTOM에 두는 이유:** 매 턴 바뀌는 값이라 시스템 프롬프트에 넣으면 캐시 접두사가 매번 깨진다.
 * 시계를 쓰지 않는 스토리에서는 아무것도 넣지 않는다(옛 스토리는 예전과 똑같이 동작한다).
 */
@Component
class StoryClockContributor : PromptContributor {
    override val slot = PromptSlot.BOTTOM
    override val order = 10
    override val name = NAME

    override fun contribute(ctx: PromptContext): String? = render(StoryClockFiles.point(ctx.storyDir))

    companion object {
        const val NAME = "story_clock"

        /** 시계를 쓰지 않으면(null) 섹션을 생략한다 */
        fun render(point: ClockPoint?): String? {
            if (point == null) return null
            val place = point.place?.let { "\n[현재 장소] $it" }.orEmpty()
            return "[현재 시각] ${StoryClock.format(point.time)}$place"
        }
    }
}
