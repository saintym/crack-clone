package com.crack.command

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class CommandsParserTest {

    @Test
    fun `DESIGN 예시 형식을 읽는다`() {
        val commands = CommandsParser.parse(
            """
            ## /일기
            설명: 주인공의 하루를 일기 형식으로 정리
            프롬프트: 지금까지의 일을 주인공 시점의 일기로 써라. 이야기는 진행하지 마라.
            """.trimIndent(),
        )

        assertThat(commands).containsExactly(
            CustomCommand("일기", "주인공의 하루를 일기 형식으로 정리", "지금까지의 일을 주인공 시점의 일기로 써라. 이야기는 진행하지 마라."),
        )
    }

    @Test
    fun `프롬프트는 다음 제목 전까지 여러 줄이고, 제목의 슬래시는 없어도 된다`() {
        val commands = CommandsParser.parse(
            """
            # 내 명령 모음 (h1은 무시)
            앞부분 설명도 무시

            ## /요약 (최근 장면)
            설명: 최근 장면 요약
            프롬프트: 최근 장면을 세 줄로 요약해라.
            - 이야기는 진행하지 마라
            ### 참고 (h3는 본문)

            ## 상태창
            현재 인물들의 상태를 표로 보여줘라.
            """.trimIndent(),
        )

        assertThat(commands.map { it.name }).containsExactly("요약", "상태창")
        assertThat(commands[0].prompt).isEqualTo("최근 장면을 세 줄로 요약해라.\n- 이야기는 진행하지 마라\n### 참고 (h3는 본문)")
        assertThat(commands[1].description).isEmpty()
        assertThat(commands[1].prompt).isEqualTo("현재 인물들의 상태를 표로 보여줘라.")
    }

    @Test
    fun `프롬프트가 비었거나 시스템 명령과 겹치거나 중복된 이름은 버린다`() {
        val commands = CommandsParser.parse(
            """
            ## /빈명령
            설명: 프롬프트 없음

            ## /기록
            프롬프트: 시스템 명령과 겹친다

            ## /OOC
            프롬프트: 대소문자만 다르다

            ## /일기
            프롬프트: 첫 번째

            ## /일기
            프롬프트: 두 번째

            ## /
            프롬프트: 이름 없음
            """.trimIndent(),
            reserved = listOf("기록", "ooc"),
        )

        assertThat(commands).containsExactly(CustomCommand("일기", "", "첫 번째"))
    }

    @Test
    fun `이번 턴 지시는 프롬프트와 인자로 만든다`() {
        val diary = CustomCommand("일기", "", "일기로 써라.")

        assertThat(CommandService.turnInstruction(diary, "/일기 오늘은 짧게")).isEqualTo("[/일기 명령] 일기로 써라.\n요청: 오늘은 짧게")
        assertThat(CommandService.turnInstruction(diary, "/일기")).isEqualTo("[/일기 명령] 일기로 써라.")
        assertThat(CommandService.argsOf(diary, "/일기장 펼친다")).isEqualTo("/일기장 펼친다")
        assertThat(CommandService.argsOf(diary, "슬래시 없이")).isEqualTo("슬래시 없이")
        assertThat(CommandService.nameIn("  /일기 오늘")).isEqualTo("일기")
        assertThat(CommandService.nameIn("그냥 대사")).isNull()
        assertThat(CommandService.normalize(" /기록 ")).isEqualTo("기록")
    }
}
