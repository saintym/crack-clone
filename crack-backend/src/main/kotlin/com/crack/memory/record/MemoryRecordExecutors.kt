package com.crack.memory.record

import com.crack.ai.support.daemonThreadFactory
import jakarta.annotation.PreDestroy
import org.springframework.stereotype.Component
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * 기억 기록 전용 실행기. 채팅 응답 스레드와 분리해 플레이를 끊지 않는다(D7).
 *
 * - [runner]: 기록 한 회를 처음부터 끝까지 돌리는 스레드(스토리당 최대 1개, 싱글 플라이트).
 * - [workers]: 캐릭터 관리자 호출용. 크기 = `crack.memory.record.concurrency`. 모든 스토리가 나눠 쓴다.
 *
 * `Executor` 타입 빈으로 노출하지 않는다(스프링 부트의 기본 작업 실행기 자동 설정이 물러나지 않게).
 */
@Component
class MemoryRecordExecutors(properties: MemoryRecordProperties) {

    val runner: ExecutorService = Executors.newCachedThreadPool(daemonThreadFactory("memory-record"))

    val workers: ExecutorService =
        Executors.newFixedThreadPool(properties.concurrency.coerceAtLeast(1), daemonThreadFactory("memory-record-worker"))

    @PreDestroy
    fun shutdown() {
        runner.shutdownNow()
        workers.shutdownNow()
    }
}
