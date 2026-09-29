package com.crack.scenario.imports.guided

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration

/**
 * 질문으로 시나리오 만들기 설정 (DESIGN.md §11.6).
 *
 * ```yaml
 * crack:
 *   create:
 *     max-rounds: 3               # 질문 라운드 상한
 *     max-questions-per-round: 5
 *     job-ttl-minutes: 30         # 진행 중인 생성 작업 캐시 TTL
 * ```
 *
 * 인물 배치 크기·동시 실행 수는 `crack.import.*`를 그대로 쓴다. 같은 파이프라인이다.
 */
@ConfigurationProperties(prefix = "crack.create")
data class CreateProperties(
    val maxRounds: Int = 3,
    val maxQuestionsPerRound: Int = 5,
    val jobTtlMinutes: Long = 30,
)

@Configuration
@EnableConfigurationProperties(CreateProperties::class)
class CreateConfig
