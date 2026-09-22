package com.crack.story

import com.crack.chat.service.ChatFileService
import com.crack.global.config.DataPathConfig
import com.crack.global.config.DataPaths
import com.crack.global.exception.NotFoundException
import com.crack.scenario.entity.Scenario
import com.crack.scenario.repository.ScenarioRepository
import com.crack.story.dto.StoryCreateRequest
import com.crack.story.entity.Story
import com.crack.story.entity.StoryStatus
import com.crack.story.files.SampleScenario
import com.crack.story.files.StoryFiles
import com.crack.story.prologue.PrologueService
import com.crack.story.repository.StoryRepository
import com.crack.story.service.StoryService
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.*
import java.nio.file.Files
import java.nio.file.Path
import java.util.*

@ExtendWith(MockitoExtension::class)
class StoryServiceTest {

    private lateinit var storyRepository: StoryRepository
    private lateinit var scenarioRepository: ScenarioRepository
    private lateinit var prologueService: PrologueService
    private lateinit var storyService: StoryService
    private lateinit var tempDir: Path

    @BeforeEach
    fun setUp() {
        tempDir = Files.createTempDirectory("crack-story-test")
        storyRepository = mock()
        scenarioRepository = mock()
        prologueService = mock()
        storyService = StoryService(storyRepository, scenarioRepository, DataPaths(DataPathConfig(dataPath = tempDir.toString())), ChatFileService(), prologueService)
    }

    @AfterEach
    fun tearDown() {
        Files.walk(tempDir)
            .sorted(Comparator.reverseOrder())
            .forEach { Files.deleteIfExists(it) }
    }

    private fun stubSave() {
        whenever(storyRepository.save(any<Story>())).thenAnswer { invocation ->
            val story = invocation.getArgument<Story>(0)
            Story(
                id = 1L,
                scenarioId = story.scenarioId,
                title = story.title,
                dirName = story.dirName,
                turnCount = story.turnCount
            )
        }
    }

    @Test
    fun `스토리 생성 시 시나리오 원본을 스토리 폴더로 복사한다`() {
        // given
        val scenarioDir = SampleScenario.copyTo(tempDir.resolve("test"))
        val scenario = Scenario(id = 1L, name = "test", title = "Test")
        whenever(scenarioRepository.findById(1L)).thenReturn(Optional.of(scenario))
        stubSave()

        // when
        val result = storyService.create(1L, StoryCreateRequest(title = "새 이야기"))

        // then
        assertEquals("새 이야기", result.title)
        assertEquals(1L, result.scenarioId)
        assertEquals(0, result.turnCount)

        // 생성된 디렉토리 확인: {data-path}/{scenario.name}/stories/{dirName}
        val captor = argumentCaptor<Story>()
        verify(storyRepository).save(captor.capture())
        val dirName = captor.firstValue.dirName
        assertTrue(dirName.all { it.isDigit() })
        val path = tempDir.resolve("test/stories").resolve(dirName)
        assertEquals(Files.readString(scenarioDir.resolve("characters/설월.md")), Files.readString(path.resolve("characters/설월.md")))
        assertTrue(Files.exists(path.resolve("world.md")))
        assertTrue(Files.exists(path.resolve("user_note.md")))
        assertTrue(Files.exists(path.resolve("story.json")))
        assertFalse(Files.exists(path.resolve("images.md")))
        assertFalse(Files.exists(path.resolve("memory/must_remember.md")), "옛 must_remember는 만들지 않는다")
        // 첫 메시지는 원본이 아니라 복사된 스토리 폴더에서 읽는다
        verify(prologueService).insertIfPresent(1L, path)
    }

    @Test
    fun `첫 메시지 저장이 실패하면 만든 스토리 폴더를 지운다`() {
        SampleScenario.copyTo(tempDir.resolve("test"))
        whenever(scenarioRepository.findById(1L)).thenReturn(Optional.of(Scenario(id = 1L, name = "test", title = "Test")))
        stubSave()
        whenever(prologueService.insertIfPresent(any(), any())).thenThrow(IllegalStateException("DB 오류"))

        assertThrows<IllegalStateException> { storyService.create(1L, StoryCreateRequest(title = "실패")) }

        val storiesDir = tempDir.resolve("test/stories")
        val leftovers = if (Files.exists(storiesDir)) Files.list(storiesDir).use { it.toList() } else emptyList()
        assertTrue(leftovers.isEmpty(), "고아 스토리 폴더가 남으면 안 된다: $leftovers")
    }

    @Test
    fun `DB 저장이 실패하면 만든 스토리 폴더를 지운다`() {
        SampleScenario.copyTo(tempDir.resolve("test"))
        whenever(scenarioRepository.findById(1L)).thenReturn(Optional.of(Scenario(id = 1L, name = "test", title = "Test")))
        whenever(storyRepository.save(any<Story>())).thenThrow(IllegalStateException("DB 오류"))

        assertThrows<IllegalStateException> { storyService.create(1L, StoryCreateRequest(title = "실패")) }

        val storiesDir = tempDir.resolve("test/stories")
        val leftovers = if (Files.exists(storiesDir)) Files.list(storiesDir).use { it.toList() } else emptyList()
        assertTrue(leftovers.isEmpty(), "고아 스토리 폴더가 남으면 안 된다: $leftovers")
        assertTrue(Files.exists(tempDir.resolve("test/world.md")))
    }

    @Test
    fun `존재하지 않는 시나리오에 스토리 생성 시 예외 발생`() {
        // given
        whenever(scenarioRepository.findById(999L)).thenReturn(Optional.empty())

        // when & then
        assertThrows<NotFoundException> {
            storyService.create(999L, StoryCreateRequest(title = "test"))
        }
    }

    @Test
    fun `스토리를 ID로 조회할 수 있다`() {
        // given
        val story = Story(id = 1L, scenarioId = 1L, title = "이야기", dirName = "1")
        whenever(storyRepository.findById(1L)).thenReturn(Optional.of(story))

        // when
        val result = storyService.findById(1L)

        // then
        assertEquals(1L, result.id)
        assertEquals("이야기", result.title)
    }

    @Test
    fun `존재하지 않는 스토리 조회 시 예외 발생`() {
        whenever(storyRepository.findById(999L)).thenReturn(Optional.empty())

        assertThrows<NotFoundException> {
            storyService.findById(999L)
        }
    }

    @Test
    fun `시나리오별 스토리 목록을 조회할 수 있다`() {
        // given
        val stories = listOf(
            Story(id = 1L, scenarioId = 1L, title = "이야기1", dirName = "1"),
            Story(id = 2L, scenarioId = 1L, title = "이야기2", dirName = "2")
        )
        whenever(storyRepository.findByScenarioIdAndStatusOrderByUpdatedAtDesc(1L)).thenReturn(stories)

        // when
        val result = storyService.findByScenarioId(1L)

        // then
        assertEquals(2, result.size)
        assertEquals("이야기1", result[0].title)
        assertEquals("이야기2", result[1].title)
    }

    @Test
    fun `스토리를 아카이브할 수 있다`() {
        // given
        val story = Story(id = 1L, scenarioId = 1L, title = "이야기", dirName = "1")
        whenever(storyRepository.findById(1L)).thenReturn(Optional.of(story))

        // when
        val result = storyService.archive(1L)

        // then
        assertEquals(StoryStatus.ARCHIVED, result.status)
    }

    @Test
    fun `스토리 삭제 시 디렉토리도 삭제된다`() {
        // given
        val storyDir = tempDir.resolve("test/stories/1700000000000")
        Files.createDirectories(storyDir.resolve("chat"))
        Files.writeString(storyDir.resolve("chat/chat_latest.md"), "content")

        val story = Story(id = 1L, scenarioId = 1L, title = "삭제", dirName = "1700000000000")
        whenever(storyRepository.findById(1L)).thenReturn(Optional.of(story))
        whenever(scenarioRepository.findById(1L)).thenReturn(Optional.of(Scenario(id = 1L, name = "test", title = "Test")))

        // when
        storyService.delete(1L)

        // then
        assertFalse(Files.exists(storyDir), "스토리 디렉토리가 삭제되어야 한다")
        verify(storyRepository).delete(story)
    }

    @Test
    fun `스토리 삭제 시 원본과 다른 스토리는 그대로다`() {
        // given
        val scenarioDir = SampleScenario.copyTo(tempDir.resolve("test"))
        val a = scenarioDir.resolve("stories/1")
        val b = scenarioDir.resolve("stories/2")
        StoryFiles.initFromScenario(scenarioDir, a)
        StoryFiles.initFromScenario(scenarioDir, b)
        val story = Story(id = 1L, scenarioId = 1L, title = "A", dirName = "1")
        whenever(storyRepository.findById(1L)).thenReturn(Optional.of(story))
        whenever(scenarioRepository.findById(1L)).thenReturn(Optional.of(Scenario(id = 1L, name = "test", title = "Test")))

        // when
        storyService.delete(1L)

        // then
        assertFalse(Files.exists(a))
        assertTrue(Files.exists(b.resolve("characters/설월.md")))
        Files.walk(SampleScenario.source).use { stream ->
            stream.filter { Files.isRegularFile(it) }.forEach { src ->
                val rel = SampleScenario.source.relativize(src).toString()
                assertEquals(Files.readString(src), Files.readString(scenarioDir.resolve(rel)), "원본 불변: $rel")
            }
        }
    }

    @Test
    fun `_legacy 스토리 삭제 시 시나리오 폴더는 보존된다`() {
        // given: _legacy 스토리의 폴더 = 시나리오 폴더 자체
        val scenarioDir = tempDir.resolve("test")
        Files.createDirectories(scenarioDir.resolve("chat"))
        Files.writeString(scenarioDir.resolve("world.md"), "# 세계관")

        val story = Story(id = 1L, scenarioId = 1L, title = "기본 스토리", dirName = DataPaths.LEGACY_DIR_NAME)
        whenever(storyRepository.findById(1L)).thenReturn(Optional.of(story))
        whenever(scenarioRepository.findById(1L)).thenReturn(Optional.of(Scenario(id = 1L, name = "test", title = "Test")))

        // when
        storyService.delete(1L)

        // then
        assertTrue(Files.exists(scenarioDir.resolve("world.md")), "시나리오 원본은 지워지면 안 된다")
        verify(storyRepository).delete(story)
    }
}
