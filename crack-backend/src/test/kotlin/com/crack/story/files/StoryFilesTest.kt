package com.crack.story.files

import com.crack.memory.docs.MemoryDocs
import com.crack.memory.docs.StoryState
import com.crack.story.settings.StorySettings
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path

class StoryFilesTest {

    @TempDir
    lateinit var tempDir: Path

    private fun scenario(): Path = SampleScenario.copyTo(tempDir.resolve("sample"))
    private fun storyDir(scenarioDir: Path, dirName: String = "1700000000000"): Path =
        scenarioDir.resolve("stories").resolve(dirName)

    @Test
    fun `원본 문서를 복사하고 images와 stories는 복사하지 않는다`() {
        val scenarioDir = scenario()
        Files.createDirectories(storyDir(scenarioDir, "999").resolve("characters"))
        Files.writeString(storyDir(scenarioDir, "999").resolve("world.md"), "다른 스토리")
        val storyDir = storyDir(scenarioDir)

        StoryFiles.initFromScenario(scenarioDir, storyDir)

        for (name in listOf("world.md", "scenario.md", "prologue.md", "keywords.md", "commands.md",
            "characters/설월.md", "characters/무극.md", "characters/protagonist.md")) {
            assertEquals(Files.readString(scenarioDir.resolve(name)), Files.readString(storyDir.resolve(name)), name)
        }
        assertFalse(Files.exists(storyDir.resolve("images.md")), "images.md는 원본을 참조한다")
        assertFalse(Files.exists(storyDir.resolve("stories")), "stories/는 복사하지 않는다")
        assertFalse(
            Files.exists(storyDir.resolve(StorySettings.FILE_NAME)),
            "원본에 없는 settings.json은 만들지 않는다",
        )
    }

    @Test
    fun `원본에 settings_json이 있으면 스토리로 복사한다`() {
        val scenarioDir = scenario()
        val json = """{"responseChars": {"min": 1200, "max": 2200}}"""
        Files.writeString(scenarioDir.resolve(StorySettings.FILE_NAME), json)
        val storyDir = storyDir(scenarioDir)

        StoryFiles.initFromScenario(scenarioDir, storyDir)

        assertEquals(json, Files.readString(storyDir.resolve(StorySettings.FILE_NAME)))
        // 스토리 폴더의 파일만 읽는다(D12): 스토리에서 고쳐도 원본은 그대로다
        Files.writeString(storyDir.resolve(StorySettings.FILE_NAME), """{"responseChars": {"min": 300, "max": 400}}""")
        assertEquals(json, Files.readString(scenarioDir.resolve(StorySettings.FILE_NAME)))
    }

    @Test
    fun `스토리 전용 파일과 story_json을 만든다`() {
        val scenarioDir = scenario()
        val storyDir = storyDir(scenarioDir)

        val meta = StoryFiles.initFromScenario(scenarioDir, storyDir)

        assertEquals(StoryFiles.USER_NOTE_INITIAL, Files.readString(storyDir.resolve("user_note.md")))
        assertEquals(StoryFiles.CHRONICLE_INITIAL, Files.readString(storyDir.resolve("chronicle.md")))
        assertEquals("[]", Files.readString(storyDir.resolve("directives.json")).trim())
        assertEquals(StoryState.EMPTY, MemoryDocs.readState(storyDir))

        assertEquals("sample", meta.scenarioName)
        assertEquals(2, meta.formatVersion)
        assertEquals(meta, StoryFiles.readMeta(storyDir))
        val json = Files.readString(storyDir.resolve("story.json"))
        assertTrue(json.contains("\"scenarioName\"") && json.contains("\"copiedAt\"") && json.contains("\"formatVersion\" : 2"), json)
    }

    @Test
    fun `없는 선택 파일은 건너뛴다`() {
        val scenarioDir = tempDir.resolve("minimal")
        Files.createDirectories(scenarioDir.resolve("characters"))
        Files.writeString(scenarioDir.resolve("world.md"), "# 세계관")
        Files.writeString(scenarioDir.resolve("characters/protagonist.md"), "# 주인공")
        // 옛 구조의 잔재는 복사하지 않는다
        Files.createDirectories(scenarioDir.resolve("memory"))
        Files.writeString(scenarioDir.resolve("memory/must_remember.md"), "옛 기억")
        Files.createDirectories(scenarioDir.resolve("chat"))
        Files.writeString(scenarioDir.resolve("chat/chat_latest.md"), "옛 대화")
        val storyDir = storyDir(scenarioDir)

        StoryFiles.initFromScenario(scenarioDir, storyDir)

        assertTrue(Files.exists(storyDir.resolve("world.md")))
        assertTrue(Files.exists(storyDir.resolve("characters/protagonist.md")))
        for (name in listOf("scenario.md", "prologue.md", "keywords.md", "commands.md", "memory", "chat")) {
            assertFalse(Files.exists(storyDir.resolve(name)), name)
        }
        assertNotNull(StoryFiles.readMeta(storyDir))
    }

    @Test
    fun `복사본은 원본과 파일을 공유하지 않는다`() {
        val scenarioDir = scenario()
        val storyDir = storyDir(scenarioDir)
        StoryFiles.initFromScenario(scenarioDir, storyDir)

        Files.writeString(storyDir.resolve("characters/설월.md"), "스토리에서 바뀜")

        assertTrue(Files.readString(scenarioDir.resolve("characters/설월.md")).contains("차갑고 신중하다"))
        assertFalse(Files.isSymbolicLink(storyDir.resolve("characters/설월.md")))
        assertFalse(Files.isSameFile(storyDir.resolve("world.md"), scenarioDir.resolve("world.md")))
    }

    @Test
    fun `이미 있는 스토리 폴더는 덮어쓰지 않는다`() {
        val scenarioDir = scenario()
        val storyDir = storyDir(scenarioDir)
        Files.createDirectories(storyDir)
        Files.writeString(storyDir.resolve("world.md"), "기존 스토리")

        assertThrows<FileAlreadyExistsException> { StoryFiles.initFromScenario(scenarioDir, storyDir) }
        assertEquals("기존 스토리", Files.readString(storyDir.resolve("world.md")))
    }

    @Test
    fun `스토리 폴더를 시나리오 폴더와 같게 줄 수 없다`() {
        val scenarioDir = scenario()
        assertThrows<IllegalArgumentException> { StoryFiles.initFromScenario(scenarioDir, scenarioDir) }
    }

    @Test
    fun `스토리 폴더를 지우면 원본과 다른 스토리는 그대로다`() {
        val scenarioDir = scenario()
        val a = storyDir(scenarioDir, "1")
        val b = storyDir(scenarioDir, "2")
        StoryFiles.initFromScenario(scenarioDir, a)
        StoryFiles.initFromScenario(scenarioDir, b)

        StoryFiles.deleteStoryDir(scenarioDir.resolve("stories"), a)

        assertFalse(Files.exists(a))
        assertTrue(Files.exists(b.resolve("story.json")))
        assertEquals(
            Files.readString(SampleScenario.source.resolve("characters/설월.md")),
            Files.readString(scenarioDir.resolve("characters/설월.md"))
        )
    }

    @Test
    fun `stories 바로 아래가 아닌 경로는 지우지 않는다`() {
        val scenarioDir = scenario()
        val storiesRoot = scenarioDir.resolve("stories")

        assertThrows<IllegalArgumentException> { StoryFiles.deleteStoryDir(storiesRoot, scenarioDir) }
        assertThrows<IllegalArgumentException> { StoryFiles.deleteStoryDir(storiesRoot, storiesRoot) }
        assertThrows<IllegalArgumentException> { StoryFiles.deleteStoryDir(storiesRoot, storiesRoot.resolve("1/../..")) }
        assertTrue(Files.exists(scenarioDir.resolve("world.md")))
    }
}
