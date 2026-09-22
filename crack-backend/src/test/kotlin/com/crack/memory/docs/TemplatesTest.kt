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
    fun `연대기 템플릿의 예시는 회차로 읽히지 않는다`() {
        val chronicle = Chronicle.read(templates.resolve("chronicle.md"))
        assertEquals(emptyList<ChronicleEntry>(), chronicle.entries())
        assertEquals(null, chronicle.summary)
        assertEquals(1, chronicle.nextNumber())
    }
}
