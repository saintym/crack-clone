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
 *     prompt-max-entries: 50    # 프롬프트에 넣는 태그 수 상한
 *     max-per-response: 3       # 한 응답에 넣게 할 인물 이미지 수 상한(스토리별로 settings.json에서 조정)
 * ```
 */
@ConfigurationProperties(prefix = "crack.image")
data class ImageProperties(
    /**
     * 프롬프트에 넣는 태그 수 상한. **활성 인물의 변형이 먼저 예산을 쓰고, 남은 것을 장면 태그가 쓴다**(T40).
     * 예산을 파일 순서로 먼저 자르면 뒤쪽 인물이 활성이어도 변형이 하나도 안 들어간다.
     */
    val promptMaxEntries: Int = 50,

    /** 한 응답에 넣게 할 인물 이미지 수 상한. 스토리별로 `settings.json`의 `maxCharacterImages`가 이긴다(T40). */
    val maxPerResponse: Int = 3,
)

@Configuration
@EnableConfigurationProperties(ImageProperties::class)
class ImageConfig
