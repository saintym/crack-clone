package com.crack.prompt.context

/**
 * "마지막으로 기억에 기록한 턴"(`stories.recorded_through_turn`)의 출처 (DESIGN.md §6.1).
 *
 * 대화 원문 범위(`turn_no > recorded_through_turn - overlap`)를 정할 때 쓴다. 칼럼과 기록 파이프라인은 T14가 만들고,
 * 구현 빈도 T14 머지 후 등록한다. **빈이 없으면 [NONE](항상 0)을 쓴다.** 이때는 기록 전과 같아서 원문 상한
 * (`crack.prompt.max-raw-turns`)만 적용된다.
 */
fun interface RecordedTurnSource {
    fun recordedThroughTurn(storyId: Long): Int

    companion object {
        /** 기본 구현. 기록한 적이 없다고 본다. */
        val NONE: RecordedTurnSource = RecordedTurnSource { 0 }
    }
}
