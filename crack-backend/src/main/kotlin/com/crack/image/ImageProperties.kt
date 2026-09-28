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
 *     prompt-max-entries: 120   # 프롬프트에 넣는 태그 수 상한
 *     max-per-response: 3       # 한 응답에 넣게 할 인물 이미지 수 상한(스토리별로 settings.json에서 조정)
 * ```
 */
@ConfigurationProperties(prefix = "crack.image")
data class ImageProperties(
    /**
     * 프롬프트에 넣는 태그 수 상한. **활성 인물의 변형이 먼저 예산을 쓰고, 남은 것을 장면 태그가 쓴다**(T40).
     * 예산을 파일 순서로 먼저 자르면 뒤쪽 인물이 활성이어도 변형이 하나도 안 들어간다.
     *
     * **이것은 "필요할 때 꺼내 쓰는" 장치가 아니다.** AI가 고를 수 있는 변형을 알아야 하므로 목록은 매 턴 들어간다.
     * 실제로 좁히는 것은 **활성 인물 선택**(§6.2)이다 — 25명 중 그 턴에 관련된 몇 명만 목록에 오른다.
     *
     * 상한이 필요한 이유는 **활성 인물 수에 상한이 없기** 때문이다. 한 턴에 인물 이름이 여러 개 나오면
     * 최악의 경우 모든 인물의 변형이 들어온다. 그것만 막는 안전장치다.
     *
     * 값 이력: T20에서 50으로 정했다(그때는 인물당 변형 1개라 25명 전체가 25항목이었다).
     * 인물당 변형이 19개가 되자 활성 인물 3명만으로 57개가 되어 잘렸다 → **120으로 올렸다**(T44).
     * 인물당 변형 수 × 예상 활성 인물 수보다 넉넉해야 한다.
     */
    val promptMaxEntries: Int = 120,

    /** 한 응답에 넣게 할 인물 이미지 수 상한. 스토리별로 `settings.json`의 `maxCharacterImages`가 이긴다(T40). */
    val maxPerResponse: Int = 3,
)

@Configuration
@EnableConfigurationProperties(ImageProperties::class)
class ImageConfig
