package com.crack.prompt.contributor

import java.nio.file.Path

/**
 * 기여자에게 넘기는 이번 턴의 정보 (DESIGN.md §6).
 *
 * @property storyDir 스토리 폴더. 기여자는 이 폴더만 읽는다(D12)
 * @property recentText 이번 입력을 뺀 대화의 최근 N개 메시지(`crack.prompt.keyword-scan-messages`) + 이번 입력. 키워드 매칭용
 * @property userInput 이번 유저 입력. 이어쓰기처럼 마지막 메시지가 ASSISTANT면 null
 * @property turnInstruction 이어쓰기, 재생성 지시, `/` 명령처럼 이번 턴에만 넣는 지시. 저장하지 않는다
 */
data class PromptContext(
    val storyId: Long,
    val storyDir: Path,
    val recentText: String,
    val userInput: String?,
    val turnInstruction: String?,
)
