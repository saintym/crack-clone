package com.crack.message.service

/**
 * 메시지 삭제([MessageService.truncateFrom]) 뒤에 호출되는 훅.
 * T14가 이 훅으로 잘린 턴에 걸친 기억 기록을 되돌린다.
 *
 * 삭제와 같은 트랜잭션 안에서 호출된다. 훅이 예외를 던지면 삭제도 롤백된다.
 */
interface TruncateHook {
    fun onTruncate(storyId: Long, minTruncatedTurn: Int)
}
