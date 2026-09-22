package com.crack.memory.docs

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class CharacterDocTest {

    private val text = """
        |# 캐릭터: 설월
        |## 기본 정보
        |- **이름**: 설월
        |- **별칭**: 월아, 설 소저，설、 월아
        |
        |## 성격
        |차갑다.
        |
        |## 기억
        |### 관계
        |- 주인공: 목숨을 빚진 뒤 경계를 풀고 신뢰하기 시작함 (t21)
        |- 무극: 오랜 원수 (t2, t9)
        |### 사건
        |- t18–21: 흑풍채 습격에서 주인공이 대신 칼을 맞음
        |  이후 사흘간 간호함
        |- t25: 옥패를 받음
        |- 날짜 모를 일
        |### 소지품 · 기술 · 신체
        |- 옥패 (주인공에게 받음, t21)
        |""".trimMargin()

    @Test
    fun `별칭 줄을 쉼표로 나눠 파싱한다`() {
        val doc = CharacterDoc("설월", text)
        assertEquals(listOf("월아", "설 소저", "설"), doc.parseAliases())
    }

    @Test
    fun `별칭 줄이 없거나 비어 있거나 안내 문구뿐이면 빈 목록이다`() {
        assertEquals(emptyList<String>(), MemoryDocs.parseAliases("# 캐릭터\n- **이름**: 무극\n"))
        assertEquals(emptyList<String>(), MemoryDocs.parseAliases("- **별칭**:\n"))
        assertEquals(emptyList<String>(), MemoryDocs.parseAliases("- **별칭**: (쉼표로 구분, 선택)\n"))
        assertEquals(listOf("흑검"), MemoryDocs.parseAliases("* **별칭**：흑검\n"))
    }

    @Test
    fun `기억 섹션의 하위 항목을 구조체로 읽는다`() {
        val memory = CharacterDoc("설월", text).memory()

        assertEquals(listOf("주인공", "무극"), memory.relations.map { it.target })
        assertEquals("목숨을 빚진 뒤 경계를 풀고 신뢰하기 시작함 (t21)", memory.relations[0].description)
        assertEquals(listOf(21), memory.relations[0].turns)
        assertEquals(listOf(2, 9), memory.relations[1].turns)

        assertEquals(3, memory.events.size)
        with(memory.events[0]) {
            assertEquals(18, fromTurn)
            assertEquals(21, toTurn)
            assertEquals("흑풍채 습격에서 주인공이 대신 칼을 맞음\n이후 사흘간 간호함", description)
        }
        assertEquals(25 to 25, memory.events[1].fromTurn to memory.events[1].toTurn)
        assertNull(memory.events[2].fromTurn)

        assertEquals(listOf("옥패 (주인공에게 받음, t21)"), memory.possessions.map { it.text })
    }

    @Test
    fun `기억 섹션만 교체하고 원본 설정은 그대로 둔다`() {
        val doc = CharacterDoc("설월", text)
        val updated = doc.withMemorySection("### 관계\n- 주인공: 연모 (t40)\n### 사건\n### 소지품·기술·신체\n")

        val before = text.substring(0, text.indexOf("## 기억"))
        assertTrue(updated.text.startsWith(before))
        assertEquals(listOf("주인공"), updated.memory().relations.map { it.target })
        assertEquals(doc.parseAliases(), updated.parseAliases())
    }

    @Test
    fun `기억 섹션이 없는 원본 문서는 끝에 추가된다`() {
        val original = "# 캐릭터: 무극\n## 기본 정보\n- **이름**: 무극\n"
        val doc = CharacterDoc("무극", original)
        assertNull(doc.memorySection)
        assertTrue(doc.memory().isEmpty())

        val memory = CharacterMemory(possessions = listOf(MemoryItem("흑검 (t3)")))
        val updated = doc.withMemory(memory)
        assertTrue(updated.text.startsWith(original))
        assertEquals(listOf(3), updated.memory().possessions.single().turns)
        assertEquals(updated.memory(), updated.withMemory(updated.memory()).memory())
    }

    @Test
    fun `파일명에서 이름을 얻고 원자적으로 쓴다`(@TempDir dir: Path) {
        val path = dir.resolve("characters/설월.md")
        CharacterDoc("설월", text).write(path)
        val read = CharacterDoc.read(path)
        assertEquals("설월", read.name)
        assertEquals(text, read.text)
        assertEquals(listOf("설월.md"), Files.list(path.parent).use { s -> s.map { it.fileName.toString() }.toList() })
    }
}
