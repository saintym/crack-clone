package com.crack.prompt.contributor

import com.crack.memory.docs.MemoryDocs
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * BOTTOM(50): 사용자가 방금 설정 문서를 고쳤다는 신호 (T39, D41).
 *
 * **왜 필요한가:** 프롬프트는 매 턴 문서에서 새로 만들어지므로 고친 내용은 다음 턴에 이미 들어간다.
 * 그런데 AI는 **무엇이 바뀌었는지, 바뀌었다는 사실 자체를** 모른다. 최근 대화 원문에 옛 말투와 옛 설정이
 * 잔뜩 남아 있으면 그쪽에 끌려간다. 이 신호가 그것을 끊는다.
 *
 * **한 번만 넣는다.** 응답이 저장되면 [com.crack.document.story.SettingsChangedHook]이 목록을 비운다.
 * BOTTOM에 두어 캐시 접두사를 깨지 않는다.
 */
@Component
class SettingsChangedContributor : PromptContributor {
    private val log = LoggerFactory.getLogger(javaClass)

    override val slot = PromptSlot.BOTTOM
    override val order = 50

    override fun contribute(ctx: PromptContext): String? {
        val changed = try {
            MemoryDocs.readState(ctx.storyDir).changedDocs
        } catch (e: Exception) {
            log.warn("state.json을 읽지 못해 설정 변경 신호를 건너뜁니다: {}", e.message)
            return null
        }
        if (changed.isEmpty()) return null
        return render(changed)
    }

    companion object {
        /** 한 번에 이름을 나열할 문서 수 상한. 넘으면 개수만 알린다 */
        const val MAX_LISTED = 8

        fun render(changed: List<String>): String {
            val list = if (changed.size <= MAX_LISTED) {
                changed.joinToString(", ") { "`$it`" }
            } else {
                changed.take(MAX_LISTED).joinToString(", ") { "`$it`" } + " 외 ${changed.size - MAX_LISTED}개"
            }
            return """[설정 변경] 작가(사용자)가 방금 다음 문서를 직접 고쳤다: $list

- 바뀐 설정을 **이번 응답부터 따른다.** 앞선 대화에서 보인 모습과 어긋나더라도 **문서가 우선이다.**
- 고쳐진 부분을 다시 읽고, 인물의 말투·성격·상태·관계를 새 설정에 맞춘다.
- **설정이 바뀌었다는 사실을 이야기 안에서 언급하지 않는다.** 인물은 그런 일이 있었다는 것을 모른다.
- 지난 일을 없던 일로 만들지는 않는다. 지금부터의 모습만 새 설정을 따른다."""
        }
    }
}
