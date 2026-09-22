package com.crack.ai.fake

import com.crack.ai.dto.AiPurpose
import com.crack.ai.dto.AiRequest
import com.crack.ai.dto.ChatMessage
import com.crack.ai.dto.MessageRole
import com.crack.ai.provider.FakeResponses
import com.crack.memory.docs.ChronicleEntry
import com.crack.memory.docs.StoryState
import com.crack.memory.record.RecordInputs
import com.crack.memory.record.RecordOutputParser
import com.crack.memory.record.RecordPrompts
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** Fake 기록 응답이 §7.3 형식을 지키고 입력에 따라 결정적으로 바뀌는지 */
class FakeRecordResponderTest {

    private val responses = FakeResponses()
    private val responder = FakeRecordResponder(responses).also { it.install() }

    private fun ask(system: String, input: String): String =
        responses.responseFor(AiRequest(system, listOf(ChatMessage(MessageRole.USER, input)), AiPurpose.RECORD))

    @Test
    fun `시나리오 관리자 - 원문에 나온 인물을 involved로 내고 고친 턴의 회차를 revised로 낸다`() {
        val input = RecordInputs.scenario(
            fromTurn = 11, toTurn = 20,
            chronicleContext = listOf(ChronicleEntry(1, 1, 10, "- 시작 (t1)")),
            chronicleSummary = null,
            state = StoryState(location = "객잔"),
            characters = listOf(RecordInputs.CharacterRef("설월", listOf("월아")), RecordInputs.CharacterRef("무극", listOf("채주"))),
            protagonistName = "한유",
            transcript = "[t11 유저] 월아, 가자\n\n[t11 서술] 둘은 길을 나섰다",
            rerecorded = "[t3 유저] 고친 말",
        )
        val out = RecordOutputParser.parseScenario(ask(RecordPrompts.SCENARIO_MANAGER, input), hasNewTurns = true)
        assertThat(out.involved).containsExactly("설월")
        assertThat(out.state.companions).containsExactly("설월")
        assertThat(out.state.location).isEqualTo("객잔")
        assertThat(out.chronicle).contains("(fake 기록, t11–t20)")
        assertThat(out.revised.keys).containsExactly(1)
        // 결정적
        assertThat(ask(RecordPrompts.SCENARIO_MANAGER, input)).isEqualTo(ask(RecordPrompts.SCENARIO_MANAGER, input))
    }

    @Test
    fun `캐릭터 관리자와 주인공 반영 - 기존 항목을 이어받는다`() {
        val doc = "# 캐릭터: 설월\n## 기본 정보\n- **이름**: 설월\n\n## 기억\n### 관계\n- 주인공: 경계함 (t2)\n### 사건\n- t1–2: 첫 만남\n### 소지품·기술·신체\n- 옥패 (t2)\n"
        val out = RecordOutputParser.parseCharacter(
            ask(RecordPrompts.CHARACTER_MANAGER, RecordInputs.character("설월", doc, "한유", "[t11 유저] 가자\n\n[t12 서술] 간다", ""))
        )
        assertThat(out.memory).contains("- 주인공: 함께 행동함 (t12)", "- t1–2: 첫 만남", "- t11–12: 설월의 기록 (fake)", "- 옥패 (t2)")
        assertThat(out.memory).doesNotContain("경계함")
        assertThat(out.protagonistChanges).isEqualTo("- [관계] 설월: 함께 행동함 (t12)")

        val changes = RecordOutputParser.parseProtagonist(
            ask(
                RecordPrompts.PROTAGONIST_MANAGER,
                RecordInputs.protagonist("한유", "### 관계\n- 설월: 경계함 (t2)\n### 소지품\n- 검 (t1)", listOf(out.protagonistChanges!!, "- [신체] 흉터 (t12)")),
            )
        )
        assertThat(changes).contains("- 설월: 함께 행동함 (t12)", "- 검 (t1)", "### 신체\n- 흉터 (t12)")
        assertThat(changes).doesNotContain("경계함")
    }

    @Test
    fun `압축 - 섹션은 하위 섹션마다 마지막 항목만, 연대기는 한 줄 요약`() {
        val section = RecordOutputParser.parseCompressed(
            ask(RecordPrompts.COMPRESS_SECTION, RecordInputs.compressSection("기억", "### 관계\n- a\n- b\n### 사건\n- c", 10))
        )
        assertThat(section).isEqualTo("### 관계\n- b\n### 사건\n- c")

        val summary = RecordOutputParser.parseCompressed(
            ask(
                RecordPrompts.COMPRESS_CHRONICLE,
                RecordInputs.compressChronicle("- 옛 요약", listOf(ChronicleEntry(1, 1, 10, "- a"), ChronicleEntry(2, 11, 20, "- b")), 100),
            )
        )
        assertThat(summary).isEqualTo("- 옛 요약\n- 회차 1–2 요약 (fake 기록, t1–20)")
    }
}
