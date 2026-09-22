package com.crack.status

import com.crack.story.files.SampleScenario
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/** 기억 문서 → 상태 구조체 (DESIGN.md §7.5). 파일만 쓰는 순수 테스트다. */
class StoryStatusServiceTest {

    @TempDir lateinit var dir: Path

    private fun write(relative: String, content: String) {
        val path = dir.resolve(relative)
        Files.createDirectories(path.parent)
        Files.writeString(path, content)
    }

    private fun appendMemory(name: String, body: String) {
        val path = dir.resolve("characters/$name.md")
        Files.writeString(path, Files.readString(path).trimEnd() + "\n\n" + body.trimIndent() + "\n")
    }

    @Test
    fun `빈 스토리 폴더는 빈 값으로 채운다`() {
        val status = StoryStatusService.build(dir, 0)

        assertThat(status.recordedThroughTurn).isEqualTo(0)
        assertThat(status.state.companions).isEmpty()
        assertThat(status.state.location).isNull()
        assertThat(status.protagonist).isNull()
        assertThat(status.characters).isEmpty()
    }

    @Test
    fun `기록 전 샘플 시나리오는 주인공만 빈 목록으로 나오고 인물은 없다`() {
        SampleScenario.copyTo(dir)

        val status = StoryStatusService.build(dir, 0)

        val protagonist = status.protagonist!!
        assertThat(protagonist.name).isEqualTo("한유")
        assertThat(protagonist.relations).isEmpty()
        assertThat(protagonist.statsAndSkills).isEmpty()
        assertThat(protagonist.possessions).isEmpty()
        assertThat(protagonist.body).isEmpty()
        assertThat(status.characters).isEmpty() // 기억이 빈 인물은 넣지 않는다
    }

    @Test
    fun `state와 주인공 변화 기록과 인물 기억을 구조화한다`() {
        SampleScenario.copyTo(dir)
        write("state.json", """{"companions": ["설월"], "location": "흑풍채 근처 숲", "time": "3일차 밤", "updatedAtTurn": 30}""")
        appendMemory(
            "protagonist",
            """
            ### 관계
            - 설월: 목숨을 구해 준 뒤 가까워짐 (t21)
            ### 스탯·기술
            - 검기 발현 (t25)
            ### 소지품
            - 흑풍채 두목의 칼 (t20)
            ### 신체
            - 왼팔에 칼자국 (t19)
            """,
        )
        appendMemory(
            "설월",
            """
            ### 관계
            - 주인공: 목숨을 빚진 뒤 경계를 풀고 신뢰하기 시작함 (t21)
            ### 사건
            - t18–21: 흑풍채 습격에서 주인공이 대신 칼을 맞음
            - 날짜 모를 일
            ### 소지품·기술·신체
            - 옥패 (주인공에게 받음, t21)
            """,
        )

        val status = StoryStatusService.build(dir, 30)

        assertThat(status.recordedThroughTurn).isEqualTo(30)
        assertThat(status.state.companions).containsExactly("설월")
        assertThat(status.state.location).isEqualTo("흑풍채 근처 숲")
        assertThat(status.state.time).isEqualTo("3일차 밤")
        assertThat(status.state.updatedAtTurn).isEqualTo(30)

        val p = status.protagonist!!
        assertThat(p.relations).containsExactly(RelationDto("설월", "목숨을 구해 준 뒤 가까워짐 (t21)", listOf(21)))
        assertThat(p.statsAndSkills).containsExactly(ItemDto("검기 발현 (t25)", listOf(25)))
        assertThat(p.possessions).containsExactly(ItemDto("흑풍채 두목의 칼 (t20)", listOf(20)))
        assertThat(p.body).containsExactly(ItemDto("왼팔에 칼자국 (t19)", listOf(19)))

        assertThat(status.characters).hasSize(1)
        val c = status.characters.single()
        assertThat(c.name).isEqualTo("설월")
        assertThat(c.companion).isTrue()
        assertThat(c.relations.single().target).isEqualTo("주인공")
        assertThat(c.events).containsExactly(
            EventDto(18, 21, "흑풍채 습격에서 주인공이 대신 칼을 맞음"),
            EventDto(null, null, "날짜 모를 일"),
        )
        assertThat(c.possessions).containsExactly(ItemDto("옥패 (주인공에게 받음, t21)", listOf(21)))
    }

    @Test
    fun `동행 인물을 동행 순서대로 앞에 두고 나머지는 이름순이다 - 별칭으로도 동행을 찾는다`() {
        SampleScenario.copyTo(dir)
        write("characters/가람.md", "# 캐릭터: 가람\n## 기본 정보\n- **이름**: 가람\n\n## 기억\n")
        for (name in listOf("설월", "무극", "가람")) {
            appendMemory(name, "### 사건\n- t1: $name 등장")
        }
        // 설월은 별칭 "월아"로 적혀 있다
        write("state.json", """{"companions": ["무극", "월아"]}""")

        val status = StoryStatusService.build(dir, 1)

        assertThat(status.characters.map { it.name }).containsExactly("무극", "설월", "가람")
        assertThat(status.characters.map { it.companion }).containsExactly(true, true, false)
    }

    @Test
    fun `동행이어도 기억이 없는 인물은 넣지 않는다`() {
        SampleScenario.copyTo(dir)
        appendMemory("무극", "### 관계\n- 주인공: 경계함 (t3)")
        write("state.json", """{"companions": ["설월"]}""")

        val status = StoryStatusService.build(dir, 3)

        assertThat(status.characters.map { it.name }).containsExactly("무극")
        assertThat(status.characters.single().companion).isFalse()
        assertThat(status.state.companions).containsExactly("설월")
    }

    @Test
    fun `깨진 state json은 빈 상태로 보고 나머지는 그대로 보여 준다`() {
        SampleScenario.copyTo(dir)
        write("state.json", "{ not json")
        appendMemory("무극", "### 관계\n- 주인공: 경계함 (t3)")

        val status = StoryStatusService.build(dir, 3)

        assertThat(status.state.companions).isEmpty()
        assertThat(status.state.location).isNull()
        assertThat(status.characters.map { it.name }).containsExactly("무극")
    }

    @Test
    fun `이름 줄이 없는 주인공은 기본 이름을 쓴다`() {
        write("characters/protagonist.md", "# 주인공\n\n## 변화 기록\n### 소지품\n- 지도 (t2)\n")

        val p = StoryStatusService.build(dir, 2).protagonist!!

        assertThat(p.name).isEqualTo("주인공")
        assertThat(p.possessions.map { it.text }).containsExactly("지도 (t2)")
    }
}
