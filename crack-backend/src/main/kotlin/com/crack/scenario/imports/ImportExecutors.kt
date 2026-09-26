package com.crack.scenario.imports

import com.crack.ai.support.daemonThreadFactory
import jakarta.annotation.PreDestroy
import org.springframework.stereotype.Component
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * 가져오기 전용 실행기.
 *
 * - [runner]: 생성 한 건을 처음부터 끝까지 도는 스레드. 요청 스레드를 붙잡지 않는다(SSE).
 * - [workers]: 인물 배치용. 크기 = `crack.import.concurrency`(기본 3).
 *
 * `Executor` 타입 빈으로 노출하지 않는다(스프링 부트 기본 작업 실행기 자동 설정과 부딪히지 않게).
 */
@Component
class ImportExecutors(properties: ImportProperties) {

    val runner: ExecutorService = Executors.newCachedThreadPool(daemonThreadFactory("scenario-import"))

    val workers: ExecutorService =
        Executors.newFixedThreadPool(properties.concurrency.coerceAtLeast(1), daemonThreadFactory("scenario-import-worker"))

    @PreDestroy
    fun shutdown() {
        runner.shutdownNow()
        workers.shutdownNow()
    }
}
