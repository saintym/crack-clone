package com.crack.chat.flow

/** 응답 생성 종류 */
enum class GenerationMode {
    /** 유저 메시지 전송 후 응답 */
    SEND,

    /** 마지막 응답에 후보 추가, 또는 응답이 없는 마지막 유저 메시지에 첫 응답 생성 */
    REGENERATE,

    /** 이어쓰기(CONTINUATION 새 턴) */
    CONTINUE,
}

/**
 * @property messageId 저장된 ASSISTANT 메시지 ID
 * @property turnNo 그 메시지의 턴 번호
 * @property turnCount 저장 직후 스토리의 턴 수(남은 메시지의 최대 turn_no)
 */
data class AfterTurnEvent(
    val storyId: Long,
    val messageId: Long,
    val turnNo: Int,
    val turnCount: Int,
    val mode: GenerationMode,
)

/**
 * ASSISTANT 응답을 저장한 뒤 호출되는 훅 (DESIGN.md §5.2). T14가 10턴 기억 기록 트리거를 여기에 건다.
 *
 * - 저장 트랜잭션이 **커밋된 뒤**, 프로바이더 스트림 스레드에서 `done` 이벤트를 보내기 **전에** 호출된다.
 *   오래 걸리는 일은 훅 안에서 배경 스레드로 넘길 것(응답 완료가 늦어진다).
 * - 훅이 던진 예외는 로그만 남기고 삼킨다. 응답 저장과 `done`에는 영향이 없다.
 * - 여러 빈이면 `@Order` 순서로 호출한다.
 */
interface AfterTurnHook {
    fun afterTurn(event: AfterTurnEvent)
}
