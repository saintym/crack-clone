package com.crack.memory.docs

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration

/**
 * 기억 문서 예산(글자 수). DESIGN.md §7.1 기본값.
 *
 * ```yaml
 * crack:
 *   memory:
 *     budget:
 *       character: 3000     # 인물 ## 기억
 *       protagonist: 4000   # 주인공 ## 변화 기록
 *       chronicle: 12000    # 연대기 회차 원문(## 장 요약 제외)
 * ```
 *
 * 넘으면 파이프라인(T14)이 압축 단계를 추가로 실행한다.
 */
@ConfigurationProperties(prefix = "crack.memory.budget")
data class MemoryBudgets(
    val character: Int = 3000,
    val protagonist: Int = 4000,
    val chronicle: Int = 12000,
) {
    enum class Kind { CHARACTER, PROTAGONIST, CHRONICLE }

    fun limit(kind: Kind): Int = when (kind) {
        Kind.CHARACTER -> character
        Kind.PROTAGONIST -> protagonist
        Kind.CHRONICLE -> chronicle
    }

    /** [length]가 예산을 **넘으면**(초과) true. 예산과 같으면 false. */
    fun exceeds(kind: Kind, length: Int): Boolean = length > limit(kind)

    fun exceeds(doc: CharacterDoc): Boolean = exceeds(Kind.CHARACTER, doc.memoryLength())
    fun exceeds(doc: ProtagonistDoc): Boolean = exceeds(Kind.PROTAGONIST, doc.changesLength())
    fun exceeds(chronicle: Chronicle): Boolean = exceeds(Kind.CHRONICLE, chronicle.rawLength())
}

@Configuration
@EnableConfigurationProperties(MemoryBudgets::class)
class MemoryDocsConfig
