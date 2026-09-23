package com.crack.global.auth

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import com.fasterxml.jackson.databind.ObjectMapper

/**
 * BUG-021 회귀 테스트(필터 포함). 비밀번호를 설정한 상태에서 위조 토큰이 API를 통과하면 안 된다.
 */
@SpringBootTest(properties = ["crack.auth.password=test-pw", "crack.auth.token-secret=test-secret"])
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuthApiTest {

    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var objectMapper: ObjectMapper

    private fun login(password: String) = mockMvc.perform(
        post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
            .content("""{"password":"$password"}"""),
    )

    @Test
    fun `위조 토큰으로는 API를 쓸 수 없다`() {
        mockMvc.perform(get("/api/scenarios").header("Authorization", "Bearer aaa.bbb.ccc"))
            .andExpect(status().isUnauthorized)
        mockMvc.perform(get("/api/scenarios").header("Authorization", "Bearer ${"x".repeat(64)}"))
            .andExpect(status().isUnauthorized)
        mockMvc.perform(get("/api/scenarios")).andExpect(status().isUnauthorized)
    }

    @Test
    fun `로그인으로 받은 토큰은 API를 쓸 수 있다`() {
        val body = login("test-pw").andExpect(status().isOk).andReturn().response.contentAsString
        val token = objectMapper.readTree(body).get("token").asText()

        mockMvc.perform(get("/api/scenarios").header("Authorization", "Bearer $token"))
            .andExpect(status().isOk)
        mockMvc.perform(get("/api/auth/verify").header("Authorization", "Bearer $token"))
            .andExpect(status().isOk)
    }

    @Test
    fun `틀린 비밀번호는 401이고 토큰도 주지 않는다`() {
        login("wrong").andExpect(status().isUnauthorized)
    }

    @Test
    fun `verify는 위조 토큰을 거부한다`() {
        mockMvc.perform(get("/api/auth/verify").header("Authorization", "Bearer aaa.bbb"))
            .andExpect(status().isUnauthorized)
    }
}
