package com.crack.story

import com.crack.chat.service.ChatFileService
import com.crack.global.config.DataPathConfig
import com.crack.global.exception.NotFoundException
import com.crack.scenario.entity.Scenario
import com.crack.scenario.repository.ScenarioRepository
import com.crack.story.dto.StoryCreateRequest
import com.crack.story.entity.Story
import com.crack.story.entity.StoryStatus
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
    private lateinit var storyService: StoryService
    private lateinit var tempDir: Path

    @BeforeEach
    fun setUp() {
        tempDir = Files.createTempDirectory("crack-story-test")
        storyRepository = mock()
        scenarioRepository = mock()
        storyService = StoryService(storyRepository, scenarioRepository, DataPathConfig(dataPath = tempDir.toString()), ChatFileService())
    }

    @AfterEach
    fun tearDown() {
        Files.walk(tempDir)
            .sorted(Comparator.reverseOrder())
            .forEach { Files.deleteIfExists(it) }
    }

    @Test
    fun `스토리 생성 시 디렉토리 구조가 생성된다`() {
        // given
        val scenario = Scenario(id = 1L, name = "test", title = "Test", dataPath = tempDir.toString())
        whenever(scenarioRepository.findById(1L)).thenReturn(Optional.of(scenario))
        whenever(storyRepository.save(any<Story>())).thenAnswer { invocation ->
            val story = invocation.getArgument<Story>(0)
            Story(
                id = 1L,
                scenarioId = story.scenarioId,
                title = story.title,
                dataPath = story.dataPath,
                turnCount = story.turnCount
            )
        }

        // when
        val result = storyService.create(1L, StoryCreateRequest(title = "새 이야기"))

        // then
        assertEquals("새 이야기", result.title)
        assertEquals(1L, result.scenarioId)
        assertEquals(0, result.turnCount)

        // 생성된 디렉토리 확인
        val storyDir = Path.of(result.id.toString()) // mock에서 실제 path 확인
        verify(storyRepository).save(argThat<Story> {
            val path = Path.of(dataPath)
            Files.exists(path.resolve("chat/chat_latest.md")) &&
                Files.exists(path.resolve("memory/must_remember.md")) &&
                Files.exists(path.resolve("characters")) &&
                Files.exists(path.resolve("chat/archive"))
        })
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
        val story = Story(id = 1L, scenarioId = 1L, title = "이야기", dataPath = "/tmp/test")
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
            Story(id = 1L, scenarioId = 1L, title = "이야기1", dataPath = "/tmp/1"),
            Story(id = 2L, scenarioId = 1L, title = "이야기2", dataPath = "/tmp/2")
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
        val story = Story(id = 1L, scenarioId = 1L, title = "이야기", dataPath = "/tmp/test")
        whenever(storyRepository.findById(1L)).thenReturn(Optional.of(story))

        // when
        val result = storyService.archive(1L)

        // then
        assertEquals(StoryStatus.ARCHIVED, result.status)
    }

    @Test
    fun `스토리 삭제 시 디렉토리도 삭제된다`() {
        // given
        val storyDir = tempDir.resolve("story-to-delete")
        Files.createDirectories(storyDir.resolve("chat"))
        Files.writeString(storyDir.resolve("chat/chat_latest.md"), "content")

        val story = Story(id = 1L, scenarioId = 1L, title = "삭제", dataPath = storyDir.toString())
        whenever(storyRepository.findById(1L)).thenReturn(Optional.of(story))

        // when
        storyService.delete(1L)

        // then
        assertFalse(Files.exists(storyDir), "스토리 디렉토리가 삭제되어야 한다")
        verify(storyRepository).delete(story)
    }
}
