package com.crack.prompt.contributor

import com.crack.memory.docs.MarkdownSections

/**
 * 문서의 **비공개 섹션**을 갈라낸다 (DESIGN.md §6.5, D38).
 *
 * `##` 제목이 `(비공개)`로 끝나는 섹션은 **기본적으로 어떤 등장인물도 모르는 정보**다.
 * 프롬프트에는 두 부분을 따로 라벨을 붙여 넣는다. 정보를 빼지는 않는다 —
 * 롤플레이 마스터가 주인공을 묘사하고 복선을 깔려면 알아야 하기 때문이다.
 *
 * 누가 아는지는 이 표시로 정하지 않는다. **그 인물 문서의 `## 알고 있는 것`(사람이 쓴다)과
 * `## 기억`(기록 파이프라인이 쓴다)에 적힌 것만 그 인물이 아는 것**이다. 인물마다 갈리는 것은
 * 그렇게 표현된다.
 */
object PrivateSections {

    /** 제목이 이 말로 끝나는 `##` 섹션이 비공개다 */
    const val MARKER = "(비공개)"

    /** 비공개 부분 앞에 붙는 한 줄. 자세한 규칙은 BASE에 한 번만 둔다(토큰 절약) */
    const val LABEL = "[비공개] 아래는 등장인물들이 모르는 정보다. 「출력 규칙」의 인지 규칙을 따른다."

    /** @property public 비공개 섹션을 뺀 본문 @property private 비공개 섹션만 이어 붙인 본문 */
    data class Split(val public: String, val private: String) {
        val hasPrivate: Boolean get() = private.isNotBlank()
    }

    fun split(text: String): Split {
        val sections = MarkdownSections.sections(text).filter { it.title.trimEnd().endsWith(MARKER) }
        if (sections.isEmpty()) return Split(text, "")

        val privateText = sections.joinToString("\n") { it.full(text).trimEnd() }
        // 뒤에서부터 지운다. 앞에서 지우면 뒤 섹션의 오프셋이 밀린다
        var publicText = text
        for (section in sections.asReversed()) {
            publicText = MarkdownSections.removeSection(publicText, section)
        }
        return Split(publicText.trimEnd() + "\n", privateText + "\n")
    }

    /**
     * 문서를 프롬프트에 넣을 형태로 만든다. 비공개 섹션이 없으면 원문 그대로다.
     *
     * ```
     * (공개 부분)
     *
     * [비공개] 아래는 등장인물들이 모르는 정보다. …
     *
     * ## 배경 (비공개)
     * …
     * ```
     */
    fun render(text: String): String {
        val split = split(text)
        if (!split.hasPrivate) return text
        return buildString {
            if (split.public.isNotBlank()) {
                append(split.public.trimEnd())
                append("\n\n")
            }
            append(LABEL)
            append("\n\n")
            append(split.private.trimEnd())
            append("\n")
        }
    }
}
