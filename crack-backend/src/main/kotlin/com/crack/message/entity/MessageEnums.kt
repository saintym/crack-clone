package com.crack.message.entity

/** 메시지 작성 주체. `ai.dto.MessageRole`(AI 요청용)과는 별개의 저장용 enum이다. */
enum class MessageRole {
    USER, ASSISTANT
}

/** 메시지 종류. 턴 번호 규칙에 영향을 준다(DESIGN.md §3 턴 규칙). */
enum class MessageKind {
    /** 일반 메시지 */
    NORMAL,

    /** 프롤로그. turn 0, seq 0, ASSISTANT 전용 */
    PROLOGUE,

    /** 이어쓰기. 유저 메시지 없이 ASSISTANT 메시지만으로 새 턴을 연다 */
    CONTINUATION,

    /** 사용자 정의 / 명령으로 보낸 유저 메시지 */
    COMMAND,
}
