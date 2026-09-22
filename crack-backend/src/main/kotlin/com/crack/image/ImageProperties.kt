package com.crack.image

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration

/**
 * 이미지 카탈로그 설정 (DESIGN.md §8.5).
 *
 * ```yaml
 * crack:
 *   image:
 *     prompt-max-entries: 50   # 프롬프트에 넣는 태그 수 상한(위에서부터)
 * ```
 */
@ConfigurationProperties(prefix = "crack.image")
data class ImageProperties(
    val promptMaxEntries: Int = 50,
)

@Configuration
@EnableConfigurationProperties(ImageProperties::class)
class ImageConfig
