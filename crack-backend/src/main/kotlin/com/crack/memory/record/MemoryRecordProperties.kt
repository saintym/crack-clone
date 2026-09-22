package com.crack.memory.record

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration

/**
 * 기억 기록 설정 (DESIGN.md §7.2).
 *
 * ```yaml
 * crack:
 *   memory:
 *     record:
 *       every-turns: 10               # 마지막 기록 이후 이만큼 턴이 쌓이면 자동 기록
 *       auto-enabled: true            # 자동 기록 켜기
 *       concurrency: 3                # 캐릭터 관리자 동시 실행 수
 *       max-attempts: 2               # 최초 1회 + 재시도 1회
 *       chronicle-context-entries: 2  # 시나리오 관리자에 넣는 연대기 최근 회차 수
 * ```
 */
@ConfigurationProperties(prefix = "crack.memory.record")
data class MemoryRecordProperties(
    val everyTurns: Int = 10,
    val autoEnabled: Boolean = true,
    val concurrency: Int = 3,
    val maxAttempts: Int = 2,
    val chronicleContextEntries: Int = 2,
)

@Configuration
@EnableConfigurationProperties(MemoryRecordProperties::class)
class MemoryRecordConfig
