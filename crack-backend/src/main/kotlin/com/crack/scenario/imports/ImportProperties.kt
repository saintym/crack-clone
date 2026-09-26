package com.crack.scenario.imports

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration

/**
 * URL 시나리오 가져오기 설정 (DESIGN.md §11.4).
 *
 * ```yaml
 * crack:
 *   import:
 *     concurrency: 3              # 인물 배치 동시 실행 수
 *     characters-per-batch: 4     # LLM 한 번에 만드는 인물 수
 *     max-bytes: 5242880          # 내려받기 상한 (5MB)
 *     timeout-seconds: 20
 *     max-redirects: 5
 *     max-context-chars: 150000   # 본문 + 데이터 블록 합계 상한
 *     max-data-block-chars: 40000
 *     job-ttl-minutes: 30
 *     allow-private-hosts: false  # 테스트 전용
 * ```
 */
@ConfigurationProperties(prefix = "crack.import")
data class ImportProperties(
    val concurrency: Int = 3,
    val charactersPerBatch: Int = 4,
    val maxBytes: Long = 5L * 1024 * 1024,
    val timeoutSeconds: Int = 20,
    val maxRedirects: Int = 5,
    val maxContextChars: Int = 150_000,
    val maxDataBlockChars: Int = 40_000,
    val jobTtlMinutes: Long = 30,
    /** 켜면 사설·루프백 검사를 끈다. 테스트에서만 쓴다. */
    val allowPrivateHosts: Boolean = false,
)

@Configuration
@EnableConfigurationProperties(ImportProperties::class)
class ImportConfig
