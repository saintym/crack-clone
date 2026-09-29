package com.crack.prompt.config

import com.crack.story.settings.ResponseChars
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
 *     raw-budget-chars: 6000     # 대화 원문에 쓸 글자 예산(§6.1, T47)
 *     raw-min-turns: 2           # 예산을 넘겨도 반드시 넣는 최소 턴 수
 *     max-raw-turns: 30          # 원문 상한(최근 턴 수). 기록이 계속 실패해도 폭주하지 않게
 *     keyword-max-active: 3      # 키워드북 동시 발동 수(§8.3). 0이면 끈다
 *     max-active-characters: 6   # 한 턴에 프롬프트에 넣을 인물 수 상한(§6.2). 0 이하면 끈다
 *     response-chars:            # 한 응답의 목표 글자 수(§6.4, D33). 절대 상한은 max + 500
 *       min: 800
 *       max: 1500
 * ```
 *
 * `response-chars`는 전역 기본값이다. 스토리 폴더의 `settings.json`이 있으면 그 값이 이긴다
 * ([com.crack.story.settings.StorySettings]).
 */
@ConfigurationProperties(prefix = "crack.prompt")
data class PromptProperties(
    val keywordScanMessages: Int = 6,

    /**
     * 대화 원문에 쓸 글자 예산 (§6.1, T47). 최근 턴부터 거꾸로 담다가 누적 글자 수가 이 값을 넘으면 멈춘다.
     *
     * **턴 수로는 크기를 예측할 수 없다** — 실측으로 한 턴이 720자에서 2,032자까지 벌어졌고 유저 입력 길이는
     * 제약이 없다. 같은 "3턴"이 2,000자일 수도 6,000자일 수도 있어서 예산으로 바꿨다.
     * 턴은 쪼개지 않는다(유저 입력과 그 응답은 함께 들어가거나 함께 빠진다).
     */
    val rawBudgetChars: Int = 6000,

    /**
     * 예산을 넘겨도 반드시 넣는 최소 턴 수 (§6.1, T47). 한 턴이 예산보다 길 수 있으므로 필요하다.
     * 1 미만은 1로 본다 — 원문이 하나도 없으면 대화가 끊긴다.
     */
    val rawMinTurns: Int = 2,

    /** 원문 턴 수의 안전 상한 (§6.1). 예산이 아무리 커도 최근 이 턴 수까지만 넣는다. */
    val maxRawTurns: Int = 30,

    val keywordMaxActive: Int = 3,

    /**
     * 한 턴에 프롬프트에 넣을 활성 인물 수 상한 (§6.2, T44). 0 이하면 상한을 끈다.
     *
     * **인물 문서가 프롬프트에서 가장 비싼 부분이다** — 실측 1명당 약 1,460자. 상한이 없으면
     * 한 턴에 인물 이름이 여러 개 나올 때 25명 전체가 들어와 `characters`만 36,000자가 된다.
     * 이미지 변형 목록도 같이 늘어난다(인물당 19변형이면 475항목).
     *
     * 6명이면 `characters` 약 8,800자, 이미지 목록 114항목으로 `crack.image.prompt-max-entries`(120) 안에 든다.
     * 동행 인물이 먼저 남고 키워드로 걸린 인물부터 잘린다.
     */
    val maxActiveCharacters: Int = 6,

    val responseChars: ResponseChars = ResponseChars(),
)

@Configuration
@EnableConfigurationProperties(PromptProperties::class)
class PromptConfig
