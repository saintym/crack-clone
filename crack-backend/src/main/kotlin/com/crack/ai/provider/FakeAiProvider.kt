package com.crack.ai.provider

import com.crack.ai.dto.AiRequest
import com.crack.ai.support.daemonThreadFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * CLI와 API 키가 없는 환경(리모트, 테스트)용 결정적 프로바이더. `crack.ai.fake.enabled=true`일 때만 등록된다.
 * 응답은 [FakeResponses]에서 목적별로 고른다. 스트림은 3~5조각의 delta 뒤에 complete를 한 번 호출한다.
 */
@Component
@ConditionalOnProperty(prefix = "crack.ai.fake", name = ["enabled"], havingValue = "true")
class FakeAiProvider(
    private val responses: FakeResponses,
    private val executor: Executor
) : AiProvider {

    @Autowired
    constructor(responses: FakeResponses) : this(responses, Executors.newCachedThreadPool(daemonThreadFactory("fake-ai")))

    override val name = NAME

    override fun chat(request: AiRequest): String = responses.responseFor(request)

    override fun stream(request: AiRequest, listener: StreamListener) {
        executor.execute {
            try {
                val text = responses.responseFor(request)
                FakeResponses.split(text).forEach(listener::onDelta)
                listener.onComplete(text)
            } catch (e: Exception) {
                listener.onError(e)
            }
        }
    }

    companion object {
        const val NAME = "fake"
    }
}
