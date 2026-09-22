package com.crack.ai.provider

import com.crack.ai.dto.AiPurpose
import com.crack.ai.dto.AiRequest
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap

/**
 * [FakeAiProvider]가 돌려줄 응답 등록소. 목적별로 응답을 등록하고, 없으면 [DEFAULT_RESPONSE]를 쓴다.
 * 테스트와 다른 작업(예: T14 기록용 응답)이 빈으로 주입받아 [register]한다.
 */
@Component
@ConditionalOnProperty(prefix = "crack.ai.fake", name = ["enabled"], havingValue = "true")
class FakeResponses {

    private val responders = ConcurrentHashMap<AiPurpose, (AiRequest) -> String>()

    fun register(purpose: AiPurpose, response: String) {
        responders[purpose] = { response }
    }

    /** 요청 내용에 따라 응답을 만들어야 할 때. 결정적이어야 한다. */
    fun register(purpose: AiPurpose, responder: (AiRequest) -> String) {
        responders[purpose] = responder
    }

    fun unregister(purpose: AiPurpose) {
        responders.remove(purpose)
    }

    fun reset() {
        responders.clear()
    }

    fun responseFor(request: AiRequest): String =
        responders[request.purpose]?.invoke(request) ?: DEFAULT_RESPONSE

    companion object {
        const val DEFAULT_RESPONSE =
            "*창밖으로 비가 내리고 있었다. 그녀는 잠시 머뭇거리다 천천히 고개를 돌렸다.* \"...왔구나. 기다리고 있었어.\""

        /**
         * 텍스트를 3~5조각으로 나눈다(텍스트가 짧으면 글자 수만큼). 조각을 이으면 원문과 같다.
         * 서러게이트 쌍은 쪼개지 않는다.
         */
        fun split(text: String): List<String> {
            if (text.isEmpty()) return emptyList()
            val codePoints = text.codePointCount(0, text.length)
            val pieces = (codePoints / 20).coerceIn(3, 5).coerceAtMost(codePoints)
            val result = mutableListOf<String>()
            var start = 0
            for (i in 1..pieces) {
                val endCp = codePoints * i / pieces
                val end = text.offsetByCodePoints(0, endCp)
                if (end > start) result.add(text.substring(start, end))
                start = end
            }
            return result
        }
    }
}
