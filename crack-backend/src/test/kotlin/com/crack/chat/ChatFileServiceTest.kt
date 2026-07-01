package com.crack.chat

import com.crack.chat.service.ChatFileService
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*
import java.nio.file.Files
import java.nio.file.Path

class ChatFileServiceTest {

    private lateinit var chatFileService: ChatFileService
    private lateinit var tempDir: Path

    @BeforeEach
    fun setUp() {
        tempDir = Files.createTempDirectory("crack-chat-test")
        chatFileService = ChatFileService()

        // 스토리 디렉토리 생성
        val storyDir = tempDir.resolve("chat")
        Files.createDirectories(storyDir)
        Files.writeString(storyDir.resolve("chat_latest.md"), "# 최근 대화\n\n")
    }

    @AfterEach
    fun tearDown() {
        Files.walk(tempDir)
            .sorted(Comparator.reverseOrder())
            .forEach { Files.deleteIfExists(it) }
    }

    @Test
    fun `사용자 메시지를 chat_latest에 추가한다`() {
        chatFileService.appendUserMessage(tempDir, "안녕하세요")

        val content = chatFileService.readChatLatest(tempDir)
        assertTrue(content.contains("## USER"))
        assertTrue(content.contains("안녕하세요"))
    }

    @Test
    fun `AI 응답을 chat_latest에 추가한다`() {
        chatFileService.appendAssistantMessage(tempDir, "[감정: 기쁨]\n\"반가워요!\"")

        val content = chatFileService.readChatLatest(tempDir)
        assertTrue(content.contains("## ASSISTANT"))
        assertTrue(content.contains("반가워요!"))
    }

    @Test
    fun `여러 턴의 대화가 순서대로 기록된다`() {
        chatFileService.appendUserMessage(tempDir, "첫 번째 메시지")
        chatFileService.appendAssistantMessage(tempDir, "첫 번째 응답")
        chatFileService.appendUserMessage(tempDir, "두 번째 메시지")
        chatFileService.appendAssistantMessage(tempDir, "두 번째 응답")

        val content = chatFileService.readChatLatest(tempDir)
        val userIdx1 = content.indexOf("첫 번째 메시지")
        val assistIdx1 = content.indexOf("첫 번째 응답")
        val userIdx2 = content.indexOf("두 번째 메시지")
        val assistIdx2 = content.indexOf("두 번째 응답")

        assertTrue(userIdx1 < assistIdx1, "첫 번째 사용자 메시지가 첫 번째 응답보다 앞에 있어야 한다")
        assertTrue(assistIdx1 < userIdx2, "첫 번째 응답이 두 번째 메시지보다 앞에 있어야 한다")
        assertTrue(userIdx2 < assistIdx2, "두 번째 메시지가 두 번째 응답보다 앞에 있어야 한다")
    }

    @Test
    fun `chat_latest 초기화 후 헤더만 남는다`() {
        chatFileService.appendUserMessage(tempDir, "메시지")
        chatFileService.appendAssistantMessage(tempDir, "응답")

        chatFileService.resetChatLatest(tempDir)

        val content = chatFileService.readChatLatest(tempDir)
        assertEquals("# 최근 대화\n\n", content)
    }

    @Test
    fun `대화를 아카이브에 저장한다`() {
        chatFileService.archiveChat(tempDir, 1, 10, "# 1~10턴 대화 원문\n\n내용...")

        val archivePath = tempDir.resolve("chat/archive/turn_001_010.md")
        assertTrue(Files.exists(archivePath), "아카이브 파일이 생성되어야 한다")

        val content = Files.readString(archivePath)
        assertTrue(content.contains("1~10턴 대화 원문"))
    }

    @Test
    fun `존재하지 않는 스토리의 chat_latest는 빈 문자열을 반환한다`() {
        val nonExistentPath = tempDir.resolve("없는스토리")
        val content = chatFileService.readChatLatest(nonExistentPath)
        assertEquals("", content)
    }

    @Test
    fun `chat 파일이 없으면 자동으로 생성한 후 메시지를 추가한다`() {
        // 새 스토리 (chat 파일 없음)
        val newStoryPath = tempDir.resolve("새스토리")
        Files.createDirectories(newStoryPath)

        chatFileService.appendUserMessage(newStoryPath, "첫 메시지")

        val content = chatFileService.readChatLatest(newStoryPath)
        assertTrue(content.contains("# 최근 대화"))
        assertTrue(content.contains("첫 메시지"))
    }
}
