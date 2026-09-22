package com.crack.global.config

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.hamcrest.Matchers.containsString

/**
 * BUG-011 회귀 테스트. 개발 서버 프록시(changeOrigin)를 거치면 Origin과 Host가 달라 모든 요청이 CORS로 처리된다.
 * 메시지 수정, 지시 켜기·끄기가 쓰는 PATCH가 허용 목록에 있어야 한다.
 */
@SpringBootTest(properties = ["crack.auth.password="])
@AutoConfigureMockMvc(addFilters = false)
@ActiveProfiles("test")
class WebConfigCorsTest {

    @Autowired lateinit var mockMvc: MockMvc

    @Test
    fun `PATCH 프리플라이트를 허용한다`() {
        mockMvc.perform(
            options("/api/stories/1/messages/1")
                .header("Origin", "http://localhost:5173")
                .header("Access-Control-Request-Method", "PATCH"),
        )
            .andExpect(status().isOk)
            .andExpect(header().string("Access-Control-Allow-Methods", containsString("PATCH")))
    }
}
