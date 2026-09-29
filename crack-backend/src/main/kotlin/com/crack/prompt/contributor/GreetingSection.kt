package com.crack.prompt.contributor

import com.crack.memory.docs.MarkdownSections

/**
 * 인물 문서의 `## 첫 인사` 제목에 **말투 참고용 라벨**을 붙인다 (DESIGN.md §6.6, D46).
 *
 * `## 첫 인사`는 그 인물이 반드시 하는 말이 아니라 **말투 샘플**이다. 그런데 URL 가져오기가 만든 문서는
 * 날짜·장소·상황이 다 들어간 300자 넘는 **완결된 장면**을 여기 넣는다. 한 시나리오의 인물 전원이
 * 같은 장면을 갖게 되어, 스토리가 어디에 있든 AI가 그 장면을 재현하려 든다.
 *
 * 섹션을 지우지 않는다 — `## 대사 예시`와 함께 말투를 지탱하는 근거라서 지우면 말투가 나빠진다.
 * 대신 제목 옆에 라벨을 붙여 **예시라는 것**을 못 박는다. 자세한 규칙은 BASE에 한 번만 둔다(토큰 절약).
 *
 * **원본 파일은 바뀌지 않는다.** 프롬프트에 넣을 때만 붙이는 순수 함수다([PrivateSections]와 같은 계열).
 */
object GreetingSection {

    /** 라벨을 붙일 `##` 섹션 제목 */
    const val TITLE = "첫 인사"

    /** 제목 뒤에 붙는 라벨. 이미 붙어 있는 제목에는 다시 붙이지 않는다 */
    const val LABEL = "(말투 참고용 — 이 인물이 반드시 하는 말이 아니다)"

    /**
     * `## 첫 인사` 제목 줄을 `## 첫 인사 (말투 참고용 — 이 인물이 반드시 하는 말이 아니다)`로 바꾼다.
     *
     * 섹션이 없으면 원문 그대로다. 본문은 한 글자도 건드리지 않고 제목 줄만 바꾼다.
     * 같은 제목이 여러 번 나오면 전부 붙인다.
     */
    fun render(text: String): String {
        val sections = MarkdownSections.sections(text).filter { it.title.trim() == TITLE }
        var result = text
        // 뒤에서부터 고친다. 앞에서 고치면 뒤 섹션의 오프셋이 밀린다
        for (section in sections.asReversed()) {
            val heading = section.heading(result)
            // 제목 줄 끝의 줄바꿈. 제목이 파일 끝이면 빈 문자열이다
            val eol = heading.substring(heading.trimEnd('\n', '\r').length)
            result = result.substring(0, section.headingStart) +
                "#".repeat(section.level) + " $TITLE $LABEL" + eol +
                result.substring(section.bodyStart)
        }
        return result
    }
}
