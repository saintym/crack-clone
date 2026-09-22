package com.crack.migration

import com.crack.memory.docs.Chronicle
import com.crack.migration.legacy.LegacyFiles
import com.crack.story.files.StoryFiles
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class LegacyFilesTest {

    private lateinit var dir: Path

    @BeforeEach
    fun setUp() {
        dir = Files.createTempDirectory("legacy-files-test")
    }

    @AfterEach
    fun tearDown() {
        Files.walk(dir).use { s -> s.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
    }

    private fun write(rel: String, content: String = "x") {
        val p = dir.resolve(rel)
        Files.createDirectories(p.parent)
        Files.writeString(p, content)
    }

    @Test
    fun `대화 파일은 아카이브를 번호 순으로, 마지막에 chat_latest`() {
        write("chat/chat_latest.md")
        write("chat/archive/turn_10_19.md")
        write("chat/archive/turn_1_9.md")
        write("chat/archive/turn_020_029.md")
        write("chat/archive/notes.md")
        write("chat/archive/turn_x.md")

        val names = LegacyFiles.chatFiles(dir).map { it.fileName.toString() }

        assertEquals(listOf("turn_1_9.md", "turn_10_19.md", "turn_020_029.md", "turn_x.md", "chat_latest.md"), names)
    }

    @Test
    fun `원래 위치가 없으면 legacy 아래에서 찾는다`() {
        write("legacy/chat/chat_latest.md")
        write("legacy/memory/must_remember.md")

        assertEquals(dir.resolve("legacy/chat/chat_latest.md"), LegacyFiles.chatFiles(dir).single())
        assertEquals(dir.resolve("legacy/memory/must_remember.md"), LegacyFiles.mustRememberFile(dir))
    }

    @Test
    fun `옛 템플릿 그대로인 필수 기억사항은 빈 유저노트가 된다`() {
        val template = "# 필수 기억사항\n\n(사용자가 직접 입력하는 \"절대 잊으면 안 되는 사항\". AI는 매 턴 이 파일을 읽어 시스템 프롬프트에 포함합니다.)\n\n-\n"
        assertEquals(StoryFiles.USER_NOTE_INITIAL, LegacyFiles.toUserNote(template))
        assertEquals(StoryFiles.USER_NOTE_INITIAL, LegacyFiles.toUserNote(""))
    }

    @Test
    fun `사용자가 쓴 필수 기억사항은 유저노트 제목 아래로 옮긴다`() {
        val text = "# 필수 기억사항\n\n(사용자가 직접 입력하는 안내)\n\n- 설월은 주인공을 사형이라 부른다\n- 무극은 왼손잡이다\n"

        val note = LegacyFiles.toUserNote(text)

        assertEquals("# 유저노트\n\n- 설월은 주인공을 사형이라 부른다\n- 무극은 왼손잡이다\n", note)
    }

    @Test
    fun `요약을 합쳐 장 요약 초안을 만들고 요약 안의 제목 줄은 굵은 글씨로 바꾼다`() {
        val text = LegacyFiles.chronicleFromSummaries(
            listOf(
                "summary_001_010.md" to "# 1~10턴 요약\n\n## 사건\n설월을 만났다.",
                "summary_011_020.md" to "# 11~20턴 요약\n\n무극과 싸웠다.",
            )
        )

        val chronicle = Chronicle.parse(text)
        val summary = chronicle.summary!!
        assertTrue(text.startsWith("# 연대기"))
        assertTrue(summary.startsWith("**턴 1–10**"))
        assertTrue(summary.contains("**사건**\n설월을 만났다."))
        assertTrue(summary.contains("**턴 11–20**\n\n무극과 싸웠다."))
        assertFalse(summary.contains("1~10턴 요약"))
        assertTrue(chronicle.entries().isEmpty(), "회차는 만들지 않는다")
    }

    @Test
    fun `요약이 없으면 빈 연대기`() {
        assertEquals(StoryFiles.CHRONICLE_INITIAL, LegacyFiles.chronicleFromSummaries(emptyList()))
    }

    @Test
    fun `요약 파일은 시작 턴 순`() {
        write("memory/summary_011_020.md")
        write("memory/summary_001_010.md")
        write("memory/must_remember.md")

        assertEquals(
            listOf("summary_001_010.md", "summary_011_020.md"),
            LegacyFiles.summaryFiles(dir).map { it.fileName.toString() },
        )
    }
}
