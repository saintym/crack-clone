package com.crack.memory.docs

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path

/** `data/_templates/`의 안내 문구가 파서에 실제 데이터로 읽히지 않는지 확인한다. */
class TemplatesTest {

    private val templates = Path.of("..", "data", "_templates")

    @Test
    fun `인물 템플릿의 별칭 안내와 기억 안내는 빈 값으로 읽힌다`() {
        val doc = CharacterDoc.read(templates.resolve("character.md"))
        assertEquals(emptyList<String>(), doc.parseAliases())
        assertTrue(doc.memorySection != null)
        assertTrue(doc.memory().isEmpty())
    }

    @Test
    fun `주인공 템플릿의 변화 기록 안내는 빈 값으로 읽힌다`() {
        val doc = ProtagonistDoc.read(templates.resolve("protagonist.md"))
        assertEquals(emptyList<String>(), doc.parseAliases())
        assertTrue(doc.changes().isEmpty())
    }

    @Test
    fun `프롤로그 템플릿의 안내와 첫 줄 태그 예시는 첫 메시지로 읽히지 않는다`() {
        val raw = java.nio.file.Files.readString(templates.resolve("prologue.md"))

        assertTrue(raw.contains("[인물: 이름]"), "첫 줄 태그 안내가 있어야 한다")
        // 전체가 HTML 주석이므로 그대로 복사해도 첫 메시지가 들어가지 않는다
        assertEquals(null, com.crack.story.prologue.Prologue.render(raw, "무명"))
    }

    @Test
    fun `연대기 템플릿의 예시는 회차로 읽히지 않는다`() {
        val chronicle = Chronicle.read(templates.resolve("chronicle.md"))
        assertEquals(emptyList<ChronicleEntry>(), chronicle.entries())
        assertEquals(null, chronicle.summary)
        assertEquals(1, chronicle.nextNumber())
    }
}
