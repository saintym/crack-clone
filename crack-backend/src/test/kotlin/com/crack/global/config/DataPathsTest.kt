package com.crack.global.config

import com.crack.global.exception.BadRequestException
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.nio.file.Files
import java.nio.file.Path

class DataPathsTest {

    private val cwd: Path = Path.of("").toAbsolutePath().normalize()

    @Test
    fun `상대 data-path는 작업 디렉터리 기준 절대 경로로 정규화된다`() {
        val paths = DataPaths(DataPathConfig(dataPath = "../data"))

        assertTrue(paths.root.isAbsolute)
        assertEquals(cwd.parent.resolve("data"), paths.root)
        assertEquals(cwd.parent.resolve("data/테스트세계"), paths.scenarioDir("테스트세계"))
    }

    @Test
    fun `점으로 시작하는 상대 경로도 정규화된다`() {
        val paths = DataPaths(DataPathConfig(dataPath = "./data/./x/.."))

        assertEquals(cwd.resolve("data"), paths.root)
    }

    @Test
    fun `절대 data-path는 그대로 쓴다`() {
        val root = Files.createTempDirectory("crack-paths-test")
        try {
            val paths = DataPaths(DataPathConfig(dataPath = root.toString()))

            assertEquals(root.toAbsolutePath().normalize(), paths.root)
            assertEquals(paths.root.resolve("마도생존기"), paths.scenarioDir("마도생존기"))
            assertEquals(
                paths.root.resolve("마도생존기/stories/1778198020225"),
                paths.storyDir("마도생존기", "1778198020225")
            )
            assertEquals(paths.root.resolve("_templates"), paths.templatesDir())
        } finally {
            Files.deleteIfExists(root)
        }
    }

    @Test
    fun `_legacy 스토리는 시나리오 폴더 자체를 가리킨다`() {
        val paths = DataPaths(DataPathConfig(dataPath = "/srv/crack/data"))

        assertEquals(paths.scenarioDir("테스트세계"), paths.storyDir("테스트세계", DataPaths.LEGACY_DIR_NAME))
        assertTrue(DataPaths.isLegacy("_legacy"))
        assertFalse(DataPaths.isLegacy("1778198020225"))
    }

    @Test
    fun `data-path를 바꾸면 같은 이름으로 새 위치를 계산한다`() {
        val before = DataPaths(DataPathConfig(dataPath = "./data"))
        val after = DataPaths(DataPathConfig(dataPath = "../data"))

        assertEquals(cwd.resolve("data/s/stories/1"), before.storyDir("s", "1"))
        assertEquals(cwd.parent.resolve("data/s/stories/1"), after.storyDir("s", "1"))
    }

    @Test
    fun `경로 조각이 아닌 이름은 거부한다`() {
        val paths = DataPaths(DataPathConfig(dataPath = "/srv/crack/data"))

        listOf("", " ", ".", "..", "a/b", "../etc", "a\\b").forEach { name ->
            assertThrows<BadRequestException>("'$name'은 거부되어야 한다") { paths.scenarioDir(name) }
        }
        assertThrows<BadRequestException> { paths.storyDir("s", "../other") }
        assertThrows<BadRequestException> { paths.storyDir("s", "..") }
    }
}
