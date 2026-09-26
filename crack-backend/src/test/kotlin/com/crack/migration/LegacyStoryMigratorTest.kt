package com.crack.migration

import com.crack.global.config.DataPaths
import com.crack.message.entity.MessageKind
import com.crack.message.entity.MessageRole
import com.crack.message.repository.MessageVariantRepository
import com.crack.message.repository.StoryMessageRepository
import com.crack.message.service.MessageService
import com.crack.migration.legacy.LegacyMigrationReport
import com.crack.migration.legacy.LegacyMigrationStatus
import com.crack.migration.legacy.LegacyStoryMigrator
import com.crack.migration.legacy.StoryMigrationResult
import com.crack.scenario.entity.Scenario
import com.crack.scenario.repository.ScenarioRepository
import com.crack.story.entity.Story
import com.crack.story.files.SampleScenario
import com.crack.story.files.StoryFiles
import com.crack.story.repository.StoryRepository
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

/**
 * 옛 스토리 이전 통합 테스트. 옛 구조는 임시 데이터 폴더에 테스트 안에서 만든다(실제 사용자 데이터는 쓰지 않는다).
 *
 * `StoryIsolationTest`와 같은 설정이라 Spring 컨텍스트를 공유한다. 인증은 비밀번호를 비워 끈다.
 * `migrateAll`은 DB의 모든 스토리를 보므로, 검증은 이 테스트가 만든 스토리의 결과만 골라서 한다.
 */
@SpringBootTest(properties = ["crack.auth.password="])
@AutoConfigureMockMvc
@ActiveProfiles("test")
class LegacyStoryMigratorTest {

    @Autowired lateinit var migrator: LegacyStoryMigrator
    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var scenarioRepository: ScenarioRepository
    @Autowired lateinit var storyRepository: StoryRepository
    @Autowired lateinit var messageRepository: StoryMessageRepository
    @Autowired lateinit var variantRepository: MessageVariantRepository
    @Autowired lateinit var messageService: MessageService
    @Autowired lateinit var dataPaths: DataPaths

    private lateinit var scenario: Scenario
    private lateinit var scenarioDir: Path

    @BeforeEach
    fun setUp() {
        val name = "legacy-" + UUID.randomUUID().toString().take(8)
        scenarioDir = SampleScenario.copyTo(dataPaths.scenarioDir(name))
        scenario = scenarioRepository.save(Scenario(name = name, title = "이전 테스트"))
    }

    @AfterEach
    fun tearDown() {
        storyRepository.findByScenarioIdOrderByUpdatedAtDesc(scenario.id).forEach { storyRepository.delete(it) }
        scenarioRepository.delete(scenario)
        if (Files.exists(scenarioDir)) {
            Files.walk(scenarioDir).use { s -> s.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
        }
    }

    // ---- 픽스처 ----

    private fun write(path: Path, content: String) {
        Files.createDirectories(path.parent)
        Files.writeString(path, content)
    }

    /**
     * 옛 대화 파일 세트. 아카이브 두 개(번호가 자릿수로 정렬되지 않게) + chat_latest.
     * 기대 결과: 메시지 10개, 턴 6.
     */
    private fun writeOldChat(baseDir: Path) {
        write(
            baseDir.resolve("chat/archive/turn_10_19.md"),
            "# 최근 대화\n\n## USER\n셋째 질문\n\n## ASSISTANT\n셋째 답\n\n## ASSISTANT\n이어지는 답\n",
        )
        write(
            baseDir.resolve("chat/archive/turn_1_9.md"),
            "# 최근 대화\n\n## USER\n첫 질문\n\n## ASSISTANT\n[감정: 기쁨, 호기심]\n*웃는다*\n\n## USER\n둘째 질문\n\n## ASSISTANT\n둘째 답\n",
        )
        write(
            baseDir.resolve("chat/chat_latest.md"),
            "# 최근 대화\n\n\n## USER\n넷째 질문\n\n## ASSISTANT\n\n\n## USER\n다섯째 질문\n\n## ASSISTANT\n다섯째 답\n",
        )
    }

    private fun writeOldMemory(baseDir: Path) {
        write(
            baseDir.resolve("memory/must_remember.md"),
            "# 필수 기억사항\n\n(사용자가 직접 입력하는 \"절대 잊으면 안 되는 사항\". AI는 매 턴 이 파일을 읽어 시스템 프롬프트에 포함합니다.)\n\n- 설월은 주인공을 사형이라 부른다\n",
        )
        write(baseDir.resolve("memory/summary_001_010.md"), "# 1~10턴 요약\n\n설월과 처음 만났다.")
    }

    /** 옛 방식(T08 이전) 스토리: stories/{dir}에 chat, memory, 스토리별 인물 덮어쓰기만 있다. */
    private fun createOldStory(dirName: String = "1700000000000"): Pair<Story, Path> {
        val dir = scenarioDir.resolve("stories").resolve(dirName)
        writeOldChat(dir)
        writeOldMemory(dir)
        write(dir.resolve("characters/설월.md"), "# 설월\n\n스토리에서 갱신된 설월\n")
        val story = storyRepository.save(Story(scenarioId = scenario.id, title = "옛 스토리", dirName = dirName))
        return story to dir
    }

    private fun resultOf(report: LegacyMigrationReport, storyId: Long): StoryMigrationResult =
        report.stories.single { it.storyId == storyId }

    private fun fixture(rel: String): String = Files.readString(SampleScenario.source.resolve(rel))

    private fun snapshot(dir: Path): Map<String, String> =
        Files.walk(dir).use { s ->
            s.filter { Files.isRegularFile(it) }.toList()
        }.associate { dir.relativize(it).toString() to Files.readString(it) }

    // ---- 테스트 ----

    @Test
    fun `옛 스토리의 대화를 순서대로 DB 메시지로 가져오고 턴을 이어서 매긴다`() {
        val (story, _) = createOldStory()

        val result = resultOf(migrator.migrateAll(), story.id)

        assertEquals(LegacyMigrationStatus.MIGRATED, result.status)
        assertEquals(10, result.importedMessages)
        assertEquals(6, result.turnCount)
        assertTrue(result.warnings.any { it.contains("빈 메시지 1개") }, result.warnings.toString())

        val messages = messageRepository.findByStoryIdOrderBySeqAsc(story.id)
        assertEquals((0..9).toList(), messages.map { it.seq })
        assertEquals(listOf(1, 1, 2, 2, 3, 3, 4, 5, 6, 6), messages.map { it.turnNo })
        val u = MessageRole.USER
        val a = MessageRole.ASSISTANT
        assertEquals(listOf(u, a, u, a, u, a, a, u, u, a), messages.map { it.role })
        assertEquals(
            listOf("첫 질문", "*웃는다*", "둘째 질문", "둘째 답", "셋째 질문", "셋째 답", "이어지는 답", "넷째 질문", "다섯째 질문", "다섯째 답"),
            messages.map { it.content },
        )
        assertEquals(MessageKind.CONTINUATION, messages[6].kind)
        assertEquals(MessageKind.NORMAL, messages[5].kind)

        // 감정 태그는 content에서 빠지고 emotion으로
        assertEquals("기쁨, 호기심", messages[1].emotion)
        assertNull(messages[3].emotion)
        // ASSISTANT는 후보 0번을 갖는다
        assertEquals(1, variantRepository.findByMessageIdOrderByVariantIndexAsc(messages[1].id).size)

        assertEquals(6, storyRepository.findById(story.id).get().turnCount)
    }

    @Test
    fun `이전 후 스토리 폴더는 새 스토리와 같은 파일 세트이고 옛 파일은 legacy 아래에 보존된다`() {
        val (story, dir) = createOldStory()

        migrator.migrateAll()

        // 원본에서 복사(없던 것만), 스토리별 인물 문서는 유지
        assertEquals("# 설월\n\n스토리에서 갱신된 설월\n", Files.readString(dir.resolve("characters/설월.md")))
        assertEquals(fixture("characters/무극.md"), Files.readString(dir.resolve("characters/무극.md")))
        assertEquals(fixture("characters/protagonist.md"), Files.readString(dir.resolve("characters/protagonist.md")))
        // 픽스처에 없는 선택 문서(settings.json)는 원본에 없으니 복사되지 않는다
        for (name in StoryFiles.COPIED_FILES.filter { Files.exists(SampleScenario.source.resolve(it)) }) {
            assertEquals(fixture(name), Files.readString(dir.resolve(name)), name)
        }
        assertFalse(Files.exists(dir.resolve("images.md")), "images.md는 복사하지 않는다")

        // 스토리 전용 파일
        assertEquals("# 유저노트\n\n- 설월은 주인공을 사형이라 부른다\n", Files.readString(dir.resolve("user_note.md")))
        val chronicle = Files.readString(dir.resolve("chronicle.md"))
        assertTrue(chronicle.startsWith("# 연대기"))
        assertTrue(chronicle.contains("## 장 요약"))
        assertTrue(chronicle.contains("설월과 처음 만났다."))
        assertEquals(StoryFiles.DIRECTIVES_INITIAL, Files.readString(dir.resolve("directives.json")))
        assertTrue(Files.exists(dir.resolve("state.json")))
        assertEquals(scenario.name, StoryFiles.readMeta(dir)!!.scenarioName)

        // 옛 파일은 legacy/로 옮겨 보존
        assertFalse(Files.exists(dir.resolve("chat")))
        assertFalse(Files.exists(dir.resolve("memory")))
        assertTrue(Files.readString(dir.resolve("legacy/chat/chat_latest.md")).contains("다섯째 답"))
        assertTrue(Files.exists(dir.resolve("legacy/chat/archive/turn_1_9.md")))
        assertTrue(Files.exists(dir.resolve("legacy/memory/must_remember.md")))
        assertTrue(Files.exists(dir.resolve("legacy/memory/summary_001_010.md")))

        assertEquals("1700000000000", storyRepository.findById(story.id).get().dirName)
    }

    @Test
    fun `두 번 실행해도 메시지와 파일이 그대로다`() {
        val (story, dir) = createOldStory()

        migrator.migrateAll()
        val filesAfterFirst = snapshot(dir)
        val messagesAfterFirst = messageRepository.findByStoryIdOrderBySeqAsc(story.id).map { it.id }

        val second = resultOf(migrator.migrateAll(), story.id)

        assertEquals(LegacyMigrationStatus.SKIPPED, second.status)
        assertEquals(0, second.importedMessages)
        assertEquals(messagesAfterFirst, messageRepository.findByStoryIdOrderBySeqAsc(story.id).map { it.id })
        assertEquals(filesAfterFirst, snapshot(dir))
    }

    @Test
    fun `중간에 끊긴 이전을 다시 실행하면 메시지를 중복으로 가져오지 않는다`() {
        val (story, dir) = createOldStory()
        // 메시지까지 가져온 뒤 story.json을 쓰기 전에 끊긴 상황
        messageService.appendUser(story.id, "이미 가져온 메시지")

        val result = resultOf(migrator.migrateAll(), story.id)

        assertEquals(LegacyMigrationStatus.MIGRATED, result.status)
        assertEquals(0, result.importedMessages)
        assertTrue(result.warnings.any { it.contains("이미 메시지가") }, result.warnings.toString())
        assertEquals(1, messageRepository.findByStoryIdOrderBySeqAsc(story.id).size)
        assertTrue(Files.exists(dir.resolve("story.json")))
        assertTrue(Files.exists(dir.resolve("legacy/chat/chat_latest.md")))
    }

    @Test
    fun `_legacy 스토리는 새 stories 폴더로 옮기고 dir_name을 바꾸며 시나리오 원본 문서는 그대로다`() {
        writeOldChat(scenarioDir)
        writeOldMemory(scenarioDir)
        val story = storyRepository.save(Story(scenarioId = scenario.id, title = "기본 스토리", dirName = DataPaths.LEGACY_DIR_NAME))

        val result = resultOf(migrator.migrateAll(), story.id)

        assertEquals(LegacyMigrationStatus.MIGRATED, result.status, result.toString())
        assertEquals(DataPaths.LEGACY_DIR_NAME, result.fromDirName)
        val dirName = storyRepository.findById(story.id).get().dirName
        assertNotEquals(DataPaths.LEGACY_DIR_NAME, dirName)
        assertTrue(dirName.all { it.isDigit() }, dirName)
        assertEquals(dirName, result.dirName)
        assertEquals(10, result.importedMessages)

        val dir = scenarioDir.resolve("stories").resolve(dirName)
        assertTrue(Files.exists(dir.resolve("story.json")))
        assertTrue(Files.exists(dir.resolve("legacy/chat/archive/turn_10_19.md")))
        assertTrue(Files.exists(dir.resolve("legacy/memory/must_remember.md")))
        assertFalse(Files.exists(dir.resolve(LegacyStoryMigrator.LEGACY_MARKER)))
        assertEquals(fixture("characters/설월.md"), Files.readString(dir.resolve("characters/설월.md")))
        assertEquals("# 유저노트\n\n- 설월은 주인공을 사형이라 부른다\n", Files.readString(dir.resolve("user_note.md")))

        // 시나리오 폴더: chat/memory는 빠지고 원본 문서는 픽스처 그대로
        assertFalse(Files.exists(scenarioDir.resolve("chat")))
        assertFalse(Files.exists(scenarioDir.resolve("memory")))
        Files.walk(SampleScenario.source).use { s ->
            s.filter { Files.isRegularFile(it) }.forEach { src ->
                val rel = SampleScenario.source.relativize(src).toString()
                assertEquals(Files.readString(src), Files.readString(scenarioDir.resolve(rel)), "원본 불변: $rel")
            }
        }

        // 두 번째 실행은 건너뛴다
        val second = resultOf(migrator.migrateAll(), story.id)
        assertEquals(LegacyMigrationStatus.SKIPPED, second.status)
        assertEquals(10, messageRepository.findByStoryIdOrderBySeqAsc(story.id).size)
        Files.list(scenarioDir.resolve("stories")).use { assertEquals(1, it.count()) }
    }

    @Test
    fun `_legacy 이전이 DB 갱신 전에 끊겼으면 표식이 있는 폴더를 다시 쓴다`() {
        val story = storyRepository.save(Story(scenarioId = scenario.id, title = "기본 스토리", dirName = DataPaths.LEGACY_DIR_NAME))
        // 표식을 쓰고 chat/memory를 옮긴 뒤 끊긴 상황
        val halfDone = scenarioDir.resolve("stories/1690000000000")
        writeOldChat(halfDone)
        writeOldMemory(halfDone)
        write(halfDone.resolve(LegacyStoryMigrator.LEGACY_MARKER), "${story.id}\n")

        val result = resultOf(migrator.migrateAll(), story.id)

        assertEquals(LegacyMigrationStatus.MIGRATED, result.status, result.toString())
        assertEquals("1690000000000", storyRepository.findById(story.id).get().dirName)
        assertEquals(10, result.importedMessages)
        Files.list(scenarioDir.resolve("stories")).use { assertEquals(1, it.count()) }
    }

    @Test
    fun `이미 v2인 스토리는 건드리지 않는다`() {
        val dir = scenarioDir.resolve("stories/1710000000000")
        StoryFiles.initFromScenario(scenarioDir, dir)
        val story = storyRepository.save(Story(scenarioId = scenario.id, title = "새 스토리", dirName = "1710000000000"))
        val before = snapshot(dir)

        val result = resultOf(migrator.migrateAll(), story.id)

        assertEquals(LegacyMigrationStatus.SKIPPED, result.status)
        assertEquals(before, snapshot(dir))
    }

    @Test
    fun `관리 API로 실행하면 스토리별 결과를 돌려준다`() {
        val (story, _) = createOldStory()

        mockMvc.perform(post("/api/admin/migrate-legacy"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.stories[?(@.storyId == ${story.id})].status").value("MIGRATED"))
            .andExpect(jsonPath("$.stories[?(@.storyId == ${story.id})].importedMessages").value(10))

        mockMvc.perform(post("/api/admin/migrate-legacy"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.stories[?(@.storyId == ${story.id})].status").value("SKIPPED"))
    }
}
