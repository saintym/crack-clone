package com.crack.ai.config

import com.crack.ai.dto.AiPurpose
import com.crack.ai.dto.ModelTier
import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * `crack.ai.*` 설정.
 *
 * ```yaml
 * crack:
 *   ai:
 *     default-provider: claude-code-cli
 *     purpose-tiers: { chat: opus, record: sonnet, utility: haiku }
 *     cli: { path: claude, timeout-seconds: 300, models: { opus: opus, sonnet: sonnet, haiku: haiku } }
 *     fake: { enabled: false }
 * ```
 */
@ConfigurationProperties(prefix = "crack.ai")
data class AiProperties(
    val defaultProvider: String = "claude-code-cli",
    /** 목적(chat/record/utility) → 등급(opus/sonnet/haiku). 대소문자 무시. 빠진 항목은 기본 매핑을 쓴다. */
    val purposeTiers: Map<String, String> = emptyMap(),
    val cli: Cli = Cli(),
    val fake: Fake = Fake(),
) {
    data class Cli(
        val path: String = "claude",
        val timeoutSeconds: Long = 300,
        val models: CliModels = CliModels(),
    )

    data class CliModels(
        val opus: String = "opus",
        val sonnet: String = "sonnet",
        val haiku: String = "haiku",
    ) {
        fun forTier(tier: ModelTier): String = when (tier) {
            ModelTier.OPUS -> opus
            ModelTier.SONNET -> sonnet
            ModelTier.HAIKU -> haiku
        }
    }

    data class Fake(
        val enabled: Boolean = false,
    )

    fun tierFor(purpose: AiPurpose): ModelTier {
        val configured = purposeTiers.entries
            .firstOrNull { it.key.equals(purpose.name, ignoreCase = true) }
            ?.value
            ?.let { value -> ModelTier.entries.firstOrNull { it.name.equals(value.trim(), ignoreCase = true) } }
        return configured ?: defaultTier(purpose)
    }

    companion object {
        fun defaultTier(purpose: AiPurpose): ModelTier = when (purpose) {
            AiPurpose.CHAT -> ModelTier.OPUS
            AiPurpose.RECORD -> ModelTier.SONNET
            AiPurpose.UTILITY -> ModelTier.HAIKU
        }
    }
}
