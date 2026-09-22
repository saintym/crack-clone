package com.crack.memory.docs

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MarkdownSectionsTest {

    private val doc = """
        |# 캐릭터: 설월
        |
        |## 기본 정보
        |- **이름**: 설월
        |
        |
        |## 기억
        |### 관계
        |- 주인공: 경계함 (t3)
        |
        |## 말투
        |  들여쓴 줄과 끝 공백
        |""".trimMargin()

    @Test
    fun `섹션 목록과 본문을 읽는다`() {
        val sections = MarkdownSections.sections(doc)
        assertEquals(listOf("기본 정보", "기억", "말투"), sections.map { it.title })
        assertEquals("### 관계\n- 주인공: 경계함 (t3)\n\n", MarkdownSections.readSection(doc, "기억"))
        assertNull(MarkdownSections.readSection(doc, "없는 섹션"))
    }

    @Test
    fun `하위 섹션은 상위 섹션 본문에 포함되고 수준별로 따로 읽힌다`() {
        val body = MarkdownSections.readSection(doc, "기억")!!
        assertEquals(listOf("관계"), MarkdownSections.sections(body, 3).map { it.title })
        assertEquals(listOf("캐릭터: 설월"), MarkdownSections.sections(doc, 1).map { it.title })
    }

    @Test
    fun `섹션을 교체하면 섹션 밖 원문은 바이트 단위로 보존된다`() {
        val replaced = MarkdownSections.replaceSection(doc, "기억", "### 관계\n- 주인공: 신뢰함 (t21)\n### 사건\n- t18–21: 습격")

        val section = MarkdownSections.find(doc, "기억")!!
        val newSection = MarkdownSections.find(replaced, "기억")!!
        // 앞부분(끝 공백과 빈 줄 두 개 포함)과 뒷부분(들여쓰기, 끝 공백 포함)이 그대로다
        assertEquals(doc.substring(0, section.headingStart), replaced.substring(0, newSection.headingStart))
        assertEquals(doc.substring(section.end), replaced.substring(newSection.end))
        assertEquals(
            "## 기억\n### 관계\n- 주인공: 신뢰함 (t21)\n### 사건\n- t18–21: 습격\n\n",
            newSection.full(replaced),
        )
    }

    @Test
    fun `같은 내용으로 교체하면 문서가 그대로다`() {
        val body = MarkdownSections.readSection(doc, "기억")!!
        assertEquals(doc, MarkdownSections.replaceSection(doc, "기억", body))
    }

    @Test
    fun `CRLF 문서는 CRLF를 유지하고 파일 끝 줄바꿈이 없으면 없는 채로 둔다`() {
        val crlf = "# 제목\r\n\r\n## 기억\r\n- 옛 항목\r\n\r\n## 말투\r\n반말"
        val replaced = MarkdownSections.replaceSection(crlf, "기억", "- 새 항목\n- 둘째")
        assertEquals("# 제목\r\n\r\n## 기억\r\n- 새 항목\r\n- 둘째\r\n\r\n## 말투\r\n반말", replaced)

        val last = MarkdownSections.replaceSection("# 제목\n## 기억\n- 옛", "기억", "- 새")
        assertEquals("# 제목\n## 기억\n- 새", last)
    }

    @Test
    fun `섹션이 없으면 문서 끝에 빈 줄 하나를 두고 추가한다`() {
        val src = "# 캐릭터: 무극\n## 기본 정보\n- **이름**: 무극\n"
        val added = MarkdownSections.replaceSection(src, "기억", "### 관계\n- 주인공: 적대 (t5)\n")
        assertEquals(src + "\n## 기억\n### 관계\n- 주인공: 적대 (t5)\n", added)
        assertTrue(added.startsWith(src))

        val noNewline = MarkdownSections.replaceSection("# 무극", "기억", "- a")
        assertEquals("# 무극\n\n## 기억\n- a\n", noNewline)

        assertEquals("## 기억\n- a\n", MarkdownSections.replaceSection("", "기억", "- a"))
    }

    @Test
    fun `빈 섹션을 채우고 비울 수 있다`() {
        val src = "## 기억\n\n## 말투\n반말\n"
        val filled = MarkdownSections.replaceSection(src, "기억", "- a")
        assertEquals("## 기억\n- a\n\n## 말투\n반말\n", filled)
        assertEquals("## 기억\n\n## 말투\n반말\n", MarkdownSections.replaceSection(filled, "기억", ""))

        val adjacent = "## 기억\n## 말투\n반말\n"
        assertEquals("## 기억\n- a\n\n## 말투\n반말\n", MarkdownSections.replaceSection(adjacent, "기억", "- a"))
    }

    @Test
    fun `코드 펜스 안의 샵 줄은 제목으로 보지 않는다`() {
        val src = "## 예시\n```\n## 기억\n```\n## 말투\n반말\n"
        assertEquals(listOf("예시", "말투"), MarkdownSections.sections(src).map { it.title })
    }
}
