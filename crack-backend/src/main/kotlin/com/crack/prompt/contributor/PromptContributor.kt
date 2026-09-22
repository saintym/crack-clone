package com.crack.prompt.contributor

/**
 * 프롬프트 섹션 기여자 (DESIGN.md §6).
 *
 * 조립기([com.crack.prompt.service.PromptAssembler])는 등록된 기여자 빈을 `(slot, order)` 순으로 불러 섹션을 모은다.
 * 새 기능(T16 지시, T17 키워드북, T20 이미지)은 조립기를 고치지 않고 이 인터페이스의 빈 하나만 추가한다.
 *
 * - [contribute]가 null이나 공백을 돌려주면 그 섹션은 생략한다.
 * - [contribute]가 예외를 던지면 조립기가 그 섹션만 빼고 로그를 남긴다. 응답 생성은 계속된다.
 * - 매 턴 호출되므로 LLM을 부르지 않는다. 스토리 폴더([PromptContext.storyDir])만 읽는다(D12).
 */
interface PromptContributor {
    /** 섹션이 들어갈 자리. */
    val slot: PromptSlot

    /** 같은 [slot] 안의 순서. 작을수록 먼저 나온다. */
    val order: Int

    /** 로그와 preview에 쓰는 섹션 이름. 기본값은 클래스 이름에서 `Contributor`를 떼고 snake_case로 바꾼 값이다. */
    val name: String get() = defaultName(javaClass)

    fun contribute(ctx: PromptContext): String?

    companion object {
        /** `KeywordBookContributor` → `keyword_book` */
        fun defaultName(type: Class<*>): String =
            type.simpleName.removeSuffix("Contributor")
                .ifEmpty { type.simpleName }
                .replace(Regex("([a-z0-9])([A-Z])"), "$1_$2")
                .lowercase()
    }
}

/**
 * 섹션 자리 (DESIGN.md §6). 선언 순서가 곧 조립 순서다.
 *
 * `BASE`…`IMAGES`는 시스템 프롬프트에 들어가고, [BOTTOM]은 마지막 유저 메시지 앞에 `[지시]` 블록으로 붙는다.
 */
enum class PromptSlot {
    BASE, WORLD, SCENARIO, PROTAGONIST, CHARACTERS, KEYWORDS, USER_NOTE, IMAGES,

    /** 지속 OOC 지시(T16), 이번 턴 지시. 가장 강하게 반영되는 위치 */
    BOTTOM;

    val inSystemPrompt: Boolean get() = this != BOTTOM
}
