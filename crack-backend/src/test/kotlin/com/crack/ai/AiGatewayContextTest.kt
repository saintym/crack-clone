package com.crack.ai

import com.crack.ai.dto.AiPurpose
import com.crack.ai.dto.AiRequest
import com.crack.ai.provider.AiProviderRegistry
import com.crack.ai.provider.FakeResponses
import com.crack.ai.service.AiGateway
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles

/** 테스트 프로필에서 fake 프로바이더가 기본으로 잡히고, 실제 AI(API/CLI)는 호출되지 않는지 확인 */
@SpringBootTest
@ActiveProfiles("test")
class AiGatewayContextTest {

    @Autowired
    lateinit var aiGateway: AiGateway

    @Autowired
    lateinit var registry: AiProviderRegistry

    @Autowired
    lateinit var fakeResponses: FakeResponses

    @AfterEach
    fun tearDown() = fakeResponses.reset()

    @Test
    fun `테스트 프로필의 기본 프로바이더는 fake이고 키 없는 claude-api는 노출되지 않는다`() {
        assertThat(registry.getDefault().name).isEqualTo("fake")
        assertThat(registry.availableProviders()).contains("fake").doesNotContain("claude-api")
    }

    @Test
    fun `게이트웨이로 fake 응답을 받는다`() {
        fakeResponses.register(AiPurpose.RECORD, "기록 결과")
        val request = AiRequest(systemPrompt = "sys", messages = emptyList(), purpose = AiPurpose.RECORD)

        assertThat(aiGateway.chat(request)).isEqualTo("기록 결과")

        val listener = RecordingListener()
        aiGateway.stream(request, listener)
        assertThat(listener.await()).isTrue()
        assertThat(listener.deltas.joinToString("")).isEqualTo("기록 결과")
        assertThat(listener.completes).containsExactly("기록 결과")
    }
}
