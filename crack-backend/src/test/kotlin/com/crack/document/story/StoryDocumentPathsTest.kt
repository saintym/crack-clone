package com.crack.document.story

import com.crack.document.story.StoryDocumentPaths.Kind
import com.crack.global.exception.BadRequestException
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.nio.file.Files
import java.nio.file.Path

class StoryDocumentPathsTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `화이트리스트 경로와 종류`() {
        val expected = mapOf(
            "world.md" to Kind.WORLD,
            "scenario.md" to Kind.SCENARIO,
            "prologue.md" to Kind.PROLOGUE,
            "characters/protagonist.md" to Kind.PROTAGONIST,
            "characters/설월.md" to Kind.CHARACTERS,
            "characters/설 소저.md" to Kind.CHARACTERS,
            "chronicle.md" to Kind.CHRONICLE,
            "user_note.md" to Kind.USER_NOTE,
            "keywords.md" to Kind.KEYWORDS,
            "commands.md" to Kind.COMMANDS,
            "settings.json" to Kind.SETTINGS,
        )
        for ((path, kind) in expected) {
            assertEquals(StoryDocumentPaths.DocPath(path, kind), StoryDocumentPaths.parse(path), path)
        }
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "", " ", "../world.md", "../../etc/passwd", "/etc/passwd", "/world.md", "~/world.md",
            "characters/../world.md", "characters/../../secret.md", "./world.md", "world.md/", "characters//a.md",
            "characters\\설월.md", "..\\world.md", "C:\\Windows\\win.ini", "C:/world.md",
            "characters/sub/a.md", "characters/.hidden.md", "characters/.md", "characters/a.txt", "characters",
            "images.md", "story.json", "state.json", "directives.json", "memory/must_remember.md",
            "stories/1/world.md", "%2e%2e/world.md", "world.md\u0000.md",
        ]
    )
    fun `화이트리스트 밖이거나 경로 조작이면 거부한다`(path: String) {
        assertThrows<BadRequestException> { StoryDocumentPaths.parse(path) }
    }

    @Test
    fun `path가 없으면 거부한다`() {
        assertThrows<BadRequestException> { StoryDocumentPaths.parse(null) }
    }

    @Test
    fun `스토리 폴더 기준으로 해석한다`() {
        val storyDir = Files.createDirectories(tempDir.resolve("story"))
        val resolved = StoryDocumentPaths.resolve(storyDir, StoryDocumentPaths.parse("characters/설월.md"))
        assertEquals(storyDir.toAbsolutePath().resolve("characters/설월.md"), resolved)
    }

    @Test
    fun `심볼릭 링크로 스토리 폴더 밖을 가리키면 거부한다`() {
        val outside = Files.createDirectories(tempDir.resolve("scenario/characters"))
        Files.writeString(outside.resolve("설월.md"), "원본")
        val storyDir = Files.createDirectories(tempDir.resolve("story"))
        Files.createSymbolicLink(storyDir.resolve("characters"), outside)
        Files.createSymbolicLink(storyDir.resolve("world.md"), tempDir.resolve("scenario/없는파일.md"))

        assertThrows<BadRequestException> {
            StoryDocumentPaths.resolve(storyDir, StoryDocumentPaths.parse("characters/설월.md"))
        }
        assertThrows<BadRequestException> {
            StoryDocumentPaths.resolve(storyDir, StoryDocumentPaths.parse("characters/새인물.md"))
        }
        assertThrows<BadRequestException> { // 끊어진 링크
            StoryDocumentPaths.resolve(storyDir, StoryDocumentPaths.parse("world.md"))
        }
    }
}
