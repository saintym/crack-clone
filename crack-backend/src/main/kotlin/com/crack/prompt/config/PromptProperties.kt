package com.crack.prompt.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration

/**
 * 프롬프트 조립 설정 (DESIGN.md §6).
 *
 * ```yaml
 * crack:
 *   prompt:
 *     keyword-scan-messages: 6   # 인물·키워드 매칭에 쓰는 최근 메시지 수(+ 이번 입력)
 *     overlap-turns: 2           # 마지막 기록 턴과 겹쳐서 넣는 원문 턴 수
 *     max-raw-turns: 30          # 원문 상한(최근 턴 수). 기록이 계속 실패해도 폭주하지 않게
 *     keyword-max-active: 3      # 키워드북 동시 발동 수(§8.3). 0이면 끈다
 * ```
 */
@ConfigurationProperties(prefix = "crack.prompt")
data class PromptProperties(
    val keywordScanMessages: Int = 6,
    val overlapTurns: Int = 2,
    val maxRawTurns: Int = 30,
    val keywordMaxActive: Int = 3,
)

@Configuration
@EnableConfigurationProperties(PromptProperties::class)
class PromptConfig
