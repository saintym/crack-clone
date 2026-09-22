package com.crack.memory

import com.crack.ai.service.AiGateway
import com.crack.chat.service.ChatFileService
import com.crack.memory.service.MemoryService
import com.crack.memory.service.StorySummaryService
import com.crack.scenario.entity.Scenario
import com.crack.scenario.repository.ScenarioRepository
import com.crack.story.entity.Story
import com.crack.story.repository.StoryRepository
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*
import org.mockito.kotlin.*
import java.nio.file.Files
import java.nio.file.Path
import java.util.*

class MemoryServiceTest {

    private lateinit var memoryService: MemoryService
    private lateinit var aiGateway: AiGateway
    private lateinit var chatFileService: ChatFileService
    private lateinit var scenarioRepository: ScenarioRepository
    private lateinit var storyRepository: StoryRepository
    private lateinit var storySummaryService: StorySummaryService
    private lateinit var tempDir: Path

    private val testStoryId = 1L
    private val testScenarioId = 1L

    @BeforeEach
    fun setUp() {
        tempDir = Files.createTempDirectory("crack-memory-test")
        aiGateway = mock()
        scenarioRepository = mock()
        storyRepository = mock()
        storySummaryService = mock()
        chatFileService = ChatFileService()

        memoryService = MemoryService(
            aiGateway = aiGateway,
            chatFileService = chatFileService,
            scenarioRepository = scenarioRepository,
            storyRepository = storyRepository,
            storySummaryService = storySummaryService
        )

        // 스토리 디렉토리 생성
        val storyDir = tempDir
        Files.createDirectories(storyDir.resolve("characters"))
        Files.createDirectories(storyDir.resolve("memory"))
        Files.createDirectories(storyDir.resolve("chat/archive"))
        Files.writeString(storyDir.resolve("chat/chat_latest.md"), "# 최근 대화\n\n")
        Files.writeString(storyDir.resolve("memory/must_remember.md"), "# 필수 기억사항\n\n- 초기 내용")

        // Story mock 설정
        val testStory = Story(
            id = testStoryId,
            scenarioId = testScenarioId,
            title = "테스트",
            dataPath = tempDir.toString(),
            turnCount = 10
        )
        whenever(storyRepository.findById(testStoryId)).thenReturn(Optional.of(testStory))
    }

    @AfterEach
    fun tearDown() {
        Files.walk(tempDir)
            .sorted(Comparator.reverseOrder())
            .forEach { Files.deleteIfExists(it) }
    }

    // --- 필수 기억사항 ---

    @Test
    fun `필수 기억사항을 읽을 수 있다`() {
        val content = memoryService.readMustRemember(testStoryId)

        assertTrue(content.contains("초기 내용"))
    }

    @Test
    fun `필수 기억사항을 수정할 수 있다`() {
        val newContent = "# 필수 기억사항\n\n- 하은은 고양이를 좋아한다\n- 주인공은 요리를 잘 한다"
        memoryService.updateMustRemember(testStoryId, newContent)

        val read = memoryService.readMustRemember(testStoryId)
        assertEquals(newContent, read)

        // 파일에도 실제 반영 확인
        val fileContent = Files.readString(tempDir.resolve("memory/must_remember.md"))
        assertEquals(newContent, fileContent)
    }

    @Test
    fun `존재하지 않는 스토리의 필수기억은 예외가 발생한다`() {
        whenever(storyRepository.findById(999L)).thenReturn(Optional.empty())

        assertThrows<Exception> {
            memoryService.readMustRemember(999L)
        }
    }

    // --- 요약 목록 ---

    @Test
    fun `요약 목록을 조회할 수 있다`() {
        val memoryDir = tempDir.resolve("memory")
        Files.writeString(memoryDir.resolve("summary_001_010.md"), "# 1~10턴 요약\n\n내용1")
        Files.writeString(memoryDir.resolve("summary_011_020.md"), "# 11~20턴 요약\n\n내용2")

        val summaries = memoryService.listSummaries(testStoryId)

        assertEquals(2, summaries.size)
        assertEquals(1, summaries[0].fromTurn)
        assertEquals(10, summaries[0].toTurn)
        assertEquals(11, summaries[1].fromTurn)
        assertEquals(20, summaries[1].toTurn)
        assertTrue(summaries[0].content.contains("내용1"))
    }

    @Test
    fun `요약 파일이 없으면 빈 목록을 반환한다`() {
        val summaries = memoryService.listSummaries(testStoryId)

        // must_remember.md가 있지만 summary_ 로 시작하는 파일은 없음
        assertTrue(summaries.isEmpty())
    }

    @Test
    fun `요약 목록은 턴 순서대로 정렬된다`() {
        val memoryDir = tempDir.resolve("memory")
        Files.writeString(memoryDir.resolve("summary_021_030.md"), "# 21~30턴")
        Files.writeString(memoryDir.resolve("summary_001_010.md"), "# 1~10턴")
        Files.writeString(memoryDir.resolve("summary_011_020.md"), "# 11~20턴")

        val summaries = memoryService.listSummaries(testStoryId)

        assertEquals(1, summaries[0].fromTurn)
        assertEquals(11, summaries[1].fromTurn)
        assertEquals(21, summaries[2].fromTurn)
    }

    // --- shouldSummarize ---

    @Test
    fun `10턴 배수일 때 요약이 필요하다`() {
        assertTrue(memoryService.shouldSummarize(10))
        assertTrue(memoryService.shouldSummarize(20))
        assertTrue(memoryService.shouldSummarize(30))
    }

    @Test
    fun `10턴 배수가 아닐 때 요약이 불필요하다`() {
        assertFalse(memoryService.shouldSummarize(1))
        assertFalse(memoryService.shouldSummarize(5))
        assertFalse(memoryService.shouldSummarize(11))
        assertFalse(memoryService.shouldSummarize(0))
    }

    // --- 요약 실행 ---

    @Test
    fun `요약 실행 시 summary 파일이 생성된다`() {
        // given
        val testScenario = Scenario(id = testScenarioId, name = "테스트", title = "테스트", dataPath = tempDir.toString())
        whenever(scenarioRepository.findById(testScenarioId)).thenReturn(Optional.of(testScenario))
        whenever(aiGateway.chat(any(), anyOrNull())).thenReturn("요약된 내용입니다. 주인공이 하은을 만났습니다.")

        // 대화 내용 추가
        chatFileService.appendUserMessage(tempDir, "안녕")
        chatFileService.appendAssistantMessage(tempDir, "반가워")

        // when
        val result = memoryService.summarize(testStoryId)

        // then
        assertEquals("summary_001_010.md", result.summaryFile)
        assertEquals("1~10", result.turnRange)
        assertTrue(result.summary.contains("요약된 내용"))

        // 파일 생성 확인
        val summaryPath = tempDir.resolve("memory/summary_001_010.md")
        assertTrue(Files.exists(summaryPath), "요약 파일이 생성되어야 한다")
        val summaryContent = Files.readString(summaryPath)
        assertTrue(summaryContent.contains("1~10턴 요약"))
    }

    @Test
    fun `요약 실행 후 chat_latest가 초기화된다`() {
        // given
        val testScenario = Scenario(id = testScenarioId, name = "테스트", title = "테스트", dataPath = tempDir.toString())
        whenever(scenarioRepository.findById(testScenarioId)).thenReturn(Optional.of(testScenario))
        whenever(aiGateway.chat(any(), anyOrNull())).thenReturn("요약")

        chatFileService.appendUserMessage(tempDir, "메시지1")
        chatFileService.appendAssistantMessage(tempDir, "응답1")

        // when
        memoryService.summarize(testStoryId)

        // then
        val chatContent = chatFileService.readChatLatest(tempDir)
        assertEquals("# 최근 대화\n\n", chatContent, "chat_latest가 초기화되어야 한다")
    }

    @Test
    fun `요약 실행 후 대화가 아카이브에 저장된다`() {
        // given
        val testScenario = Scenario(id = testScenarioId, name = "테스트", title = "테스트", dataPath = tempDir.toString())
        whenever(scenarioRepository.findById(testScenarioId)).thenReturn(Optional.of(testScenario))
        whenever(aiGateway.chat(any(), anyOrNull())).thenReturn("요약")

        chatFileService.appendUserMessage(tempDir, "메시지1")

        // when
        memoryService.summarize(testStoryId)

        // then
        val archivePath = tempDir.resolve("chat/archive/turn_001_010.md")
        assertTrue(Files.exists(archivePath), "아카이브 파일이 생성되어야 한다")
        val archiveContent = Files.readString(archivePath)
        assertTrue(archiveContent.contains("메시지1"))
    }

    @Test
    fun `요약 실행 시 캐릭터 문서가 갱신된다`() {
        // given - scenarioDataPath에 캐릭터 파일 생성
        val scenarioDir = tempDir.resolve("scenario_base")
        Files.createDirectories(scenarioDir.resolve("characters"))
        Files.writeString(scenarioDir.resolve("characters/하은.md"), "# 캐릭터: 하은\n\n## 주요 사건 기록\n")

        val testScenario = Scenario(id = testScenarioId, name = "테스트", title = "테스트", dataPath = scenarioDir.toString())
        whenever(scenarioRepository.findById(testScenarioId)).thenReturn(Optional.of(testScenario))
        whenever(aiGateway.chat(any(), anyOrNull())).thenReturn("갱신된 캐릭터 문서 내용")

        chatFileService.appendUserMessage(tempDir, "대화")

        // when
        val result = memoryService.summarize(testStoryId)

        // then
        assertTrue(result.charactersUpdated.contains("하은"), "하은 캐릭터가 갱신 목록에 있어야 한다")
        // Claude가 2번 호출됨: 요약 1번 + 캐릭터 갱신 N번
        verify(aiGateway, atLeast(2)).chat(any(), anyOrNull())
    }

    @Test
    fun `20턴째 요약 시 올바른 파일명이 생성된다`() {
        // given
        val testStory20 = Story(
            id = testStoryId,
            scenarioId = testScenarioId,
            title = "테스트",
            dataPath = tempDir.toString(),
            turnCount = 20
        )
        whenever(storyRepository.findById(testStoryId)).thenReturn(Optional.of(testStory20))

        val testScenario = Scenario(id = testScenarioId, name = "테스트", title = "테스트", dataPath = tempDir.toString())
        whenever(scenarioRepository.findById(testScenarioId)).thenReturn(Optional.of(testScenario))
        whenever(aiGateway.chat(any(), anyOrNull())).thenReturn("요약")

        chatFileService.appendUserMessage(tempDir, "대화")

        // when
        val result = memoryService.summarize(testStoryId)

        // then
        assertEquals("summary_011_020.md", result.summaryFile)
        assertEquals("11~20", result.turnRange)
    }
}
