package com.crack.memory.docs

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class StoryStateTest {

    @Test
    fun `state json 왕복`(@TempDir dir: Path) {
        val state = StoryState(listOf("설월", "무극"), "흑풍채 근처 숲", "3일차 밤", 30)
        MemoryDocs.writeState(dir, state)
        assertTrue(Files.isRegularFile(dir.resolve("state.json")))
        assertEquals(state, MemoryDocs.readState(dir))
    }

    @Test
    fun `파일이 없으면 빈 기본값`(@TempDir dir: Path) {
        val state = MemoryDocs.readState(dir)
        assertEquals(StoryState.EMPTY, state)
        assertEquals(emptyList<String>(), state.companions)
        assertFalse(Files.exists(dir.resolve("state.json")))
    }

    @Test
    fun `DESIGN 예시 JSON을 읽고 모르는 필드와 null은 무시한다`() {
        val json = """{"companions": ["설월"], "location": "흑풍채 근처 숲", "time": "3일차 밤", "updatedAtTurn": 30}"""
        assertEquals(StoryState(listOf("설월"), "흑풍채 근처 숲", "3일차 밤", 30), StoryState.fromJson(json))

        val partial = StoryState.fromJson("""{"companions": null, "location": "숲", "mood": "긴장"}""")
        assertEquals(StoryState(location = "숲"), partial)
        assertEquals(StoryState.EMPTY, StoryState.fromJson("  "))
    }

    @Test
    fun `원자적 쓰기는 기존 파일을 덮어쓰고 임시 파일을 남기지 않는다`(@TempDir dir: Path) {
        val path = dir.resolve("state.json")
        StoryState(listOf("a")).write(path)
        StoryState(listOf("b")).write(path)
        assertEquals(listOf("b"), StoryState.read(path).companions)
        assertEquals(listOf("state.json"), Files.list(dir).use { s -> s.map { it.fileName.toString() }.toList() })
    }
}
