package com.crack.memory.record

import com.crack.message.entity.MessageKind
import com.crack.message.entity.MessageRole
import com.crack.message.entity.StoryMessage
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class RecordOutputParserTest {

    @Test
    fun `시나리오 관리자 출력을 읽는다 - 태그 밖 글과 코드 펜스는 무시`() {
        val text = """
            알겠습니다. 결과입니다.
            <chronicle>
            ```markdown
            - 숲에서 습격을 받음 (t11)
            ```
            </chronicle>
            <state>```json
            {"companions":["설월", " ", "설월"],"location":" 숲 ","time":"3일차 밤","updatedAtTurn":99}
            ```</state>
            <involved>설월, 무극
            - 흑풍채주</involved>
            <revised entry="2">- 고친 회차 (t5)</revised>
            <revised entry='3'>없음</revised>
        """.trimIndent()

        val out = RecordOutputParser.parseScenario(text, hasNewTurns = true)

        assertThat(out.chronicle).isEqualTo("- 숲에서 습격을 받음 (t11)")
        assertThat(out.state.companions).containsExactly("설월")
        assertThat(out.state.location).isEqualTo("숲")
        assertThat(out.state.updatedAtTurn).isNull()
        assertThat(out.involved).containsExactly("설월", "무극", "흑풍채주")
        assertThat(out.revised).containsExactlyEntriesOf(mapOf(2 to "- 고친 회차 (t5)"))
    }

    @Test
    fun `필수 태그가 없거나 비어 있으면 실패`() {
        val ok = "<chronicle>- a (t1)</chronicle><state>{}</state><involved>없음</involved>"
        assertThat(RecordOutputParser.parseScenario(ok, true).involved).isEmpty()

        assertThatThrownBy { RecordOutputParser.parseScenario("<state>{}</state><involved>없음</involved>", true) }
            .isInstanceOf(RecordFormatException::class.java)
        assertThatThrownBy { RecordOutputParser.parseScenario("<chronicle> </chronicle><state>{}</state><involved/>", true) }
            .isInstanceOf(RecordFormatException::class.java)
        assertThatThrownBy { RecordOutputParser.parseScenario("<chronicle>없음</chronicle><state>{}</state><involved></involved>", true) }
            .isInstanceOf(RecordFormatException::class.java)
        assertThatThrownBy { RecordOutputParser.parseScenario("<chronicle>- a</chronicle><state>깨진 json</state><involved></involved>", true) }
            .isInstanceOf(RecordFormatException::class.java)
        assertThatThrownBy { RecordOutputParser.parseScenario("<chronicle>- a</chronicle><state>{\"companions\": 3}</state><involved></involved>", true) }
            .isInstanceOf(RecordFormatException::class.java)
        assertThatThrownBy { RecordOutputParser.parseScenario("<chronicle>- a</chronicle><state>{}</state>", true) }
            .isInstanceOf(RecordFormatException::class.java)
    }

    @Test
    fun `새 턴이 없는 재반영 기록은 chronicle 없음을 허용한다`() {
        val out = RecordOutputParser.parseScenario("<chronicle>없음</chronicle><state>{}</state><involved>설월</involved>", hasNewTurns = false)
        assertThat(out.chronicle).isNull()
        assertThat(out.involved).containsExactly("설월")
    }

    @Test
    fun `캐릭터 관리자 출력 - 제목 줄은 떼고 protagonist_changes 없음은 null`() {
        val out = RecordOutputParser.parseCharacter(
            "<memory>\n## 기억\n### 관계\n- 한유: 신뢰 (t3)\n</memory>\n<protagonist_changes>없음</protagonist_changes>"
        )
        assertThat(out.memory).isEqualTo("### 관계\n- 한유: 신뢰 (t3)")
        assertThat(out.protagonistChanges).isNull()

        val withChanges = RecordOutputParser.parseCharacter(
            "<memory>### 관계\n- a</memory><protagonist_changes>- [관계] 설월: 동료 (t3)</protagonist_changes>"
        )
        assertThat(withChanges.protagonistChanges).isEqualTo("- [관계] 설월: 동료 (t3)")

        assertThatThrownBy { RecordOutputParser.parseCharacter("<memory>## 기억</memory><protagonist_changes/>") }
            .isInstanceOf(RecordFormatException::class.java)
        assertThatThrownBy { RecordOutputParser.parseCharacter("<memory>### 관계</memory>") }
            .isInstanceOf(RecordFormatException::class.java)
    }

    @Test
    fun `주인공 반영과 압축 출력`() {
        assertThat(RecordOutputParser.parseProtagonist("<changes>## 변화 기록\n### 관계\n- 설월: 동료</changes>"))
            .isEqualTo("### 관계\n- 설월: 동료")
        assertThatThrownBy { RecordOutputParser.parseProtagonist("<changes>없음</changes>") }
            .isInstanceOf(RecordFormatException::class.java)
        assertThat(RecordOutputParser.parseCompressed("<compressed>## 기억\n- 요약</compressed>", "기억")).isEqualTo("- 요약")
        assertThatThrownBy { RecordOutputParser.parseCompressed("요약만 있음") }.isInstanceOf(RecordFormatException::class.java)
    }

    @Test
    fun `입력 조립 - 프롤로그는 빼고 빈 구획은 없음`() {
        val messages = listOf(
            StoryMessage(storyId = 1, seq = 0, turnNo = 0, role = MessageRole.ASSISTANT, kind = MessageKind.PROLOGUE, content = "프롤로그"),
            StoryMessage(storyId = 1, seq = 1, turnNo = 1, role = MessageRole.USER, content = " 안녕 "),
            StoryMessage(storyId = 1, seq = 2, turnNo = 1, role = MessageRole.ASSISTANT, content = "응답"),
        )
        assertThat(RecordInputs.transcript(messages)).isEqualTo("[t1 유저] 안녕\n\n[t1 서술] 응답")

        val input = RecordInputs.character("설월", "# 캐릭터: 설월", "한유", "[t1 유저] 안녕", "")
        assertThat(RecordOutputParser.extractTag(input, "character")).isEqualTo("설월")
        assertThat(RecordOutputParser.extractTag(input, "rerecorded")).isEqualTo("없음")
        assertThat(RecordInputs.rangeLabel(3, 2)).isEqualTo("없음")
        assertThat(RecordInputs.rangeLabel(1, 10)).isEqualTo("t1–t10")
    }

    @Test
    fun `회차 제목 줄이 붙어 온 chronicle과 revised 본문에서 제목을 뗀다 - BUG-012`() {
        val text = """
            <chronicle>
            ## 회차 2 (턴 4–6)
            - 능선을 넘음 (t5)
            </chronicle>
            <state>{}</state>
            <involved>없음</involved>
            <revised entry="1">
            ### 회차 1 (턴 1-3)
            - 고친 회차 (t3)
            </revised>
        """.trimIndent()

        val out = RecordOutputParser.parseScenario(text, hasNewTurns = true)

        assertThat(out.chronicle).isEqualTo("- 능선을 넘음 (t5)")
        assertThat(out.revised).containsExactlyEntriesOf(mapOf(1 to "- 고친 회차 (t3)"))
    }

    @Test
    fun `회차 제목이 아닌 제목 줄은 그대로 둔다`() {
        assertThat(RecordOutputParser.stripEntryHeading("## 장 요약\n- a")).isEqualTo("## 장 요약\n- a")
        assertThat(RecordOutputParser.stripEntryHeading("- 회차 2 이야기 (t5)")).isEqualTo("- 회차 2 이야기 (t5)")
        assertThat(RecordOutputParser.stripEntryHeading("## 회차 10 (턴 91–100)")).isEmpty()
    }

    @Test
    fun `제목 줄만 온 chronicle은 빈 본문으로 보고 실패한다`() {
        assertThatThrownBy {
            RecordOutputParser.parseScenario(
                "<chronicle>## 회차 2 (턴 4–6)</chronicle><state>{}</state><involved>없음</involved>",
                hasNewTurns = true,
            )
        }.isInstanceOf(RecordFormatException::class.java)
    }

    @Test
    fun `모든 관리자 프롬프트에 기록 기준이 그대로 들어 있고 출력 태그를 지시한다`() {
        val prompts = mapOf(
            RecordPrompts.SCENARIO_MANAGER to listOf("<chronicle>", "<state>", "<involved>"),
            RecordPrompts.CHARACTER_MANAGER to listOf("<memory>", "<protagonist_changes>"),
            RecordPrompts.PROTAGONIST_MANAGER to listOf("<changes>"),
            RecordPrompts.COMPRESS_SECTION to listOf("<compressed>"),
            RecordPrompts.COMPRESS_CHRONICLE to listOf("<compressed>"),
        )
        prompts.forEach { (prompt, tags) ->
            assertThat(prompt).contains(RecordPrompts.RECORD_CRITERIA)
            assertThat(prompt).doesNotContain("{{")
            assertThat(prompt).doesNotStartWith(" ")
            tags.forEach { assertThat(prompt).contains(it) }
        }
    }
}
