package com.crack.story.prologue

import com.crack.story.files.SampleScenario
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class PrologueTest {

    private lateinit var tempDir: Path

    @BeforeEach
    fun setUp() {
        tempDir = Files.createTempDirectory("crack-prologue-test")
    }

    @AfterEach
    fun tearDown() {
        Files.walk(tempDir).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
    }

    @Test
    fun `user 자리표시자를 이름으로 바꾼다`() {
        assertEquals("한유, 어서 와요. 한유?", Prologue.render("{{user}}, 어서 와요. {{ User }}?", "한유"))
    }

    @Test
    fun `앞뒤 공백을 자르고 줄바꿈은 LF로 맞춘다`() {
        assertEquals("첫 줄\n\n둘째 줄", Prologue.render("\r\n\n  첫 줄\r\n\r\n둘째 줄\n\n", "한유"))
    }

    @Test
    fun `HTML 주석은 빼고 주석뿐이면 null이다`() {
        assertEquals("본문", Prologue.render("<!-- 안내\n여러 줄 -->\n본문\n<!-- 끝 -->", "한유"))
        assertNull(Prologue.render("<!-- 안내 문구만 있다 -->\n\n", "한유"))
        assertNull(Prologue.render("   \n\t\n", "한유"))
    }

    @Test
    fun `스토리 폴더의 prologue와 주인공 이름으로 본문을 만든다`() {
        val dir = SampleScenario.copyTo(tempDir.resolve("story"))
        Files.writeString(dir.resolve("prologue.md"), "*{{user}}가 객잔에 들어섰다.*\n")

        assertEquals("*한유가 객잔에 들어섰다.*", Prologue.read(dir))
    }

    @Test
    fun `prologue 파일이 없으면 null이다`() {
        val dir = SampleScenario.copyTo(tempDir.resolve("story"))
        Files.delete(dir.resolve("prologue.md"))

        assertNull(Prologue.read(dir))
    }

    @Test
    fun `주인공 이름이 없으면 당신을 쓴다`() {
        val dir = Files.createDirectories(tempDir.resolve("story"))
        assertEquals("당신", Prologue.userName(dir), "주인공 파일이 없을 때")

        Files.createDirectories(dir.resolve("characters"))
        Files.writeString(dir.resolve("characters/protagonist.md"), "# 주인공\n\n## 기본 정보\n- **이름**:\n- **나이**: 20\n")
        assertEquals("당신", Prologue.userName(dir), "이름 값이 비었을 때")

        Files.writeString(dir.resolve("characters/protagonist.md"), "# 주인공\n\n- **이름**: (주인공 이름)\n")
        assertEquals("당신", Prologue.userName(dir), "안내 문구뿐일 때")

        Files.writeString(dir.resolve("prologue.md"), "{{user}}은 눈을 떴다.")
        assertEquals("당신은 눈을 떴다.", Prologue.read(dir))
    }
}
