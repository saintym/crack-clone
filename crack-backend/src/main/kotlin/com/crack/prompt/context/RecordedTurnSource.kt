package com.crack.prompt.context

/**
 * "마지막으로 기억에 기록한 턴"(`stories.recorded_through_turn`)의 출처 (DESIGN.md §6.1).
 *
 * 대화 원문 범위(§6.1)에서 **프롤로그(턴 0)를 뺄지**를 정할 때 쓴다. 1 이상이면 프롤로그는 이미 연대기에 남았으므로
 * 원문에 넣지 않는다. 범위 자체는 글자 예산(`crack.prompt.raw-budget-chars`)이 정한다(T47).
 * 칼럼과 기록 파이프라인은 T14가 만들고, 구현 빈도 T14 머지 후 등록한다.
 * **빈이 없으면 [NONE](항상 0)을 쓴다.** 이때는 기록 전과 같아서 프롤로그까지 원문에 들어간다.
 */
fun interface RecordedTurnSource {
    fun recordedThroughTurn(storyId: Long): Int

    companion object {
        /** 기본 구현. 기록한 적이 없다고 본다. */
        val NONE: RecordedTurnSource = RecordedTurnSource { 0 }
    }
}
