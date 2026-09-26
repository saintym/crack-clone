package com.crack.scenario.imports

import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

/**
 * 가져오기 API (DESIGN.md §11.2). 실제 URL을 내려받지 않고 거부 경로만 본다.
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc(addFilters = false) // 로컬 application.yml의 비밀번호로 AuthFilter가 끼지 않게
class ScenarioImportApiTest {

    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var objectMapper: ObjectMapper
    @Autowired lateinit var properties: ImportProperties

    private fun analyze(url: String) = mockMvc.perform(
        post("/api/scenarios/import/analyze")
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(mapOf("url" to url))),
    )

    @Test
    fun `설정 기본값이 DESIGN과 같다`() {
        assertThat(properties.concurrency).isEqualTo(3)
        assertThat(properties.charactersPerBatch).isEqualTo(4)
        assertThat(properties.maxBytes).isEqualTo(5L * 1024 * 1024)
        assertThat(properties.timeoutSeconds).isEqualTo(20)
        assertThat(properties.maxContextChars).isEqualTo(150_000)
        assertThat(properties.jobTtlMinutes).isEqualTo(30)
        assertThat(properties.allowPrivateHosts).isFalse()
    }

    @Test
    fun `빈 주소는 400이다`() {
        analyze("").andExpect(status().isBadRequest)
    }

    @Test
    fun `내부 주소는 400이다`() {
        analyze("http://127.0.0.1:8080/a.html").andExpect(status().isBadRequest)
        analyze("http://169.254.169.254/latest/meta-data").andExpect(status().isBadRequest)
        analyze("file:///etc/passwd").andExpect(status().isBadRequest)
    }

    @Test
    fun `없는 jobId로 생성하면 404다`() {
        mockMvc.perform(
            post("/api/scenarios/import/없는-job/confirm")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"name":"무림","answers":{}}"""),
        ).andExpect(status().isNotFound)
    }
}
