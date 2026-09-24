package com.crack.migration.legacy

import com.crack.global.config.DataPaths
import com.crack.memory.docs.AtomicFiles
import com.crack.memory.docs.MemoryDocs
import com.crack.memory.docs.StoryState
import com.crack.message.dto.ResponseTags
import com.crack.message.entity.MessageKind
import com.crack.message.entity.MessageRole
import com.crack.message.repository.StoryMessageRepository
import com.crack.message.service.MessageService
import com.crack.scenario.entity.Scenario
import com.crack.scenario.repository.ScenarioRepository
import com.crack.story.entity.Story
import com.crack.story.files.StoryFiles
import com.crack.story.files.StoryMeta
import com.crack.story.repository.StoryRepository
import jakarta.persistence.EntityManager
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * 옛 스토리를 v2 구조로 옮기는 1회성 이전기 (T09).
 *
 * **대상:** DB의 모든 스토리(보관 포함) 중 폴더에 `story.json`이 없는 것. `story.json`은 맨 마지막에 쓰므로
 * 끝까지 성공한 스토리는 다음 실행에서 건너뛴다(멱등). 중간에 실패하면 다음 실행이 이어서 한다.
 *
 * **스토리 하나의 순서**
 * 0. `_legacy` 스토리(폴더 = 시나리오 폴더)는 먼저 새 `stories/{밀리초}` 폴더를 만들고 시나리오 폴더의 `chat/`, `memory/`를
 *    그리로 옮긴 뒤 DB `dir_name`을 바꾼다. 새 폴더에는 표식 파일([LEGACY_MARKER])을 두어, 도중에 끊겨도 다음 실행이 같은 폴더를 다시 쓴다.
 * 1. 원본 문서 중 스토리에 없는 것만 복사한다. 이미 있는 스토리별 `characters/{이름}.md`는 유지한다.
 * 2. `memory/must_remember.md` → `user_note.md` (없을 때만)
 * 3. `memory/summary_*.md` → `chronicle.md`의 `## 장 요약` 초안 (없을 때만). `directives.json`, `state.json`도 없으면 만든다.
 * 4. `chat/archive/turn_*.md`(번호 순) → `chat/chat_latest.md` 순으로 메시지를 DB에 가져온다. 한 트랜잭션이다.
 *    스토리에 이미 DB 메시지가 있으면 가져오지 않는다(중복 방지, 경고).
 * 5. 옛 `chat/`, `memory/`를 지우지 않고 `legacy/` 아래로 옮긴다.
 * 6. `story.json`을 쓴다.
 *
 * 결과는 [StoryFiles.initFromScenario]로 만든 새 스토리와 같은 파일 세트에 `legacy/`가 더해진 모양이다.
 */
@Component
class LegacyStoryMigrator(
    private val storyRepository: StoryRepository,
    private val scenarioRepository: ScenarioRepository,
    private val messageRepository: StoryMessageRepository,
    private val messageService: MessageService,
    private val dataPaths: DataPaths,
    private val entityManager: EntityManager,
    transactionManager: PlatformTransactionManager,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val tx = TransactionTemplate(transactionManager)
    private val lock = Any()

    /** 모든 대상 스토리를 이전한다. 동시에 두 번 돌지 않도록 직렬화한다. */
    fun migrateAll(): LegacyMigrationReport = synchronized(lock) {
        val scenarios = scenarioRepository.findAll().associateBy { it.id }
        val stories = storyRepository.findAll().sortedBy { it.id }
        val results = stories.map { story ->
            val scenario = scenarios[story.scenarioId]
            if (scenario == null) {
                StoryMigrationResult(
                    story.id, "?", story.title, story.dirName, story.dirName, LegacyMigrationStatus.FAILED,
                    error = "시나리오를 찾을 수 없습니다: ${story.scenarioId}",
                )
            } else {
                migrateSafely(story, scenario)
            }
        }
        val report = LegacyMigrationReport.of(results)
        logReport(report)
        report
    }

    private fun migrateSafely(story: Story, scenario: Scenario): StoryMigrationResult =
        try {
            migrate(story, scenario)
        } catch (e: Exception) {
            log.error("스토리 이전 실패: storyId={}, scenario={}", story.id, scenario.name, e)
            StoryMigrationResult(
                story.id, scenario.name, story.title, story.dirName, story.dirName, LegacyMigrationStatus.FAILED,
                error = "${e.javaClass.simpleName}: ${e.message}",
            )
        }

    private fun migrate(story: Story, scenario: Scenario): StoryMigrationResult {
        val fromDirName = story.dirName
        val currentDir = dataPaths.storyDir(scenario.name, fromDirName)
        if (!DataPaths.isLegacy(fromDirName) && StoryFiles.readMeta(currentDir) != null) {
            return StoryMigrationResult(
                story.id, scenario.name, story.title, fromDirName, fromDirName, LegacyMigrationStatus.SKIPPED,
            )
        }

        val warnings = mutableListOf<String>()
        val scenarioDir = dataPaths.scenarioDir(scenario.name)
        if (!Files.isDirectory(scenarioDir)) warnings += "시나리오 폴더가 없어 원본 문서 없이 이전한다: ${scenario.name}"

        // 0. _legacy → stories/{새 폴더}
        val dirName = if (DataPaths.isLegacy(fromDirName)) relocateLegacy(story, scenario, warnings) else fromDirName
        val storyDir = dataPaths.storyDir(scenario.name, dirName)
        if (!Files.isDirectory(storyDir)) {
            warnings += "스토리 폴더가 없어 새로 만든다"
            Files.createDirectories(storyDir)
        }

        // 1~3. 문서
        copyMissingOriginals(scenarioDir, storyDir)
        writeUserNoteIfMissing(storyDir, warnings)
        writeChronicleIfMissing(storyDir)
        if (!Files.exists(storyDir.resolve(StoryFiles.DIRECTIVES_FILE))) {
            AtomicFiles.writeString(storyDir.resolve(StoryFiles.DIRECTIVES_FILE), StoryFiles.DIRECTIVES_INITIAL)
        }
        if (!Files.exists(MemoryDocs.statePath(storyDir))) MemoryDocs.writeState(storyDir, StoryState.EMPTY)

        // 4. 메시지
        val imported = importMessages(story.id, storyDir, warnings)

        // 5. 옛 파일 보존
        moveOldFilesToLegacy(storyDir, warnings)

        // 6. 완료 표시
        Files.deleteIfExists(storyDir.resolve(LEGACY_MARKER))
        StoryMeta.create(scenario.name).write(storyDir.resolve(StoryFiles.STORY_JSON))

        val turnCount = messageRepository.findMaxTurnNo(story.id) ?: 0
        return StoryMigrationResult(
            story.id, scenario.name, story.title, fromDirName, dirName, LegacyMigrationStatus.MIGRATED,
            importedMessages = imported, turnCount = turnCount, warnings = warnings,
        )
    }

    // ---- 0. _legacy ----

    /**
     * `_legacy` 스토리의 `chat/`, `memory/`를 새 스토리 폴더로 옮기고 DB `dir_name`을 바꾼다. 새 `dir_name`을 돌려준다.
     *
     * 표식 파일을 먼저 쓰고 파일을 옮긴 뒤 DB를 바꾼다. 중간에 끊겨도 다음 실행이 표식으로 같은 폴더를 찾아 이어 간다.
     * 시나리오 원본 문서(world.md, characters/ 등)는 옮기지 않는다. 1단계에서 복사한다.
     */
    private fun relocateLegacy(story: Story, scenario: Scenario, warnings: MutableList<String>): String {
        val scenarioDir = dataPaths.scenarioDir(scenario.name)
        val storiesRoot = scenarioDir.resolve(DataPaths.STORIES_DIR)
        val dirName = findMarkedDir(storiesRoot, story.id) ?: newDirName(scenario.name)
        val storyDir = dataPaths.storyDir(scenario.name, dirName)
        Files.createDirectories(storyDir)
        AtomicFiles.writeString(storyDir.resolve(LEGACY_MARKER), "${story.id}\n")

        var moved = false
        for (name in LegacyFiles.MOVED_DIRS) {
            val source = scenarioDir.resolve(name)
            if (!Files.isDirectory(source)) continue
            val target = storyDir.resolve(name)
            if (Files.exists(target)) {
                warnings += "시나리오 폴더의 $name/ 를 옮기지 못했다(대상이 이미 있음). 시나리오 폴더에 그대로 둔다"
                continue
            }
            Files.move(source, target)
            moved = true
        }
        if (!moved && LegacyFiles.oldDir(storyDir, LegacyFiles.CHAT_DIR) == null) {
            warnings += "시나리오 폴더에 옛 chat/이 없다(다른 _legacy 스토리가 먼저 가져갔거나 대화가 없다)"
        }

        tx.executeWithoutResult {
            entityManager.createQuery("UPDATE Story s SET s.dirName = :dirName WHERE s.id = :id")
                .setParameter("dirName", dirName)
                .setParameter("id", story.id)
                .executeUpdate()
        }
        log.info("_legacy 스토리 폴더 이전: storyId={}, {} → stories/{}", story.id, scenario.name, dirName)
        return dirName
    }

    /** 이 스토리를 위해 만들다 만 폴더(표식 파일에 스토리 ID가 적힌 것)를 찾는다. */
    private fun findMarkedDir(storiesRoot: Path, storyId: Long): String? {
        if (!Files.isDirectory(storiesRoot)) return null
        return Files.list(storiesRoot).use { s ->
            s.filter { Files.isDirectory(it) }
                .filter { dir ->
                    AtomicFiles.readStringOrNull(dir.resolve(LEGACY_MARKER))?.trim() == storyId.toString()
                }
                .map { it.fileName.toString() }
                .sorted()
                .findFirst()
                .orElse(null)
        }
    }

    /** 새 스토리 폴더 이름: 현재 시각 밀리초. 이미 있으면 1씩 올린다 (DESIGN.md §2). */
    private fun newDirName(scenarioName: String): String {
        var millis = System.currentTimeMillis()
        while (Files.exists(dataPaths.storyDir(scenarioName, millis.toString()))) millis++
        return millis.toString()
    }

    // ---- 1~3. 문서 ----

    /** 원본 문서 중 스토리에 없는 것만 복사한다. 있는 파일(특히 스토리별 인물 문서)은 덮어쓰지 않는다. */
    private fun copyMissingOriginals(scenarioDir: Path, storyDir: Path) {
        for (name in StoryFiles.COPIED_FILES) copyIfMissing(scenarioDir.resolve(name), storyDir.resolve(name))
        val sourceChars = scenarioDir.resolve(StoryFiles.CHARACTERS_DIR)
        val targetChars = storyDir.resolve(StoryFiles.CHARACTERS_DIR)
        Files.createDirectories(targetChars)
        if (!Files.isDirectory(sourceChars)) return
        Files.list(sourceChars).use { s ->
            s.filter { p ->
                val n = p.fileName.toString()
                n.endsWith(".md") && !n.startsWith(".") && Files.isRegularFile(p)
            }.sorted().toList()
        }.forEach { copyIfMissing(it, targetChars.resolve(it.fileName.toString())) }
    }

    private fun copyIfMissing(source: Path, target: Path) {
        if (!Files.isRegularFile(source) || Files.exists(target)) return
        Files.copy(source, target) // 심볼릭 링크는 따라가 내용만 복사한다(StoryFiles와 같은 규칙)
    }

    private fun writeUserNoteIfMissing(storyDir: Path, warnings: MutableList<String>) {
        val target = storyDir.resolve(StoryFiles.USER_NOTE_FILE)
        if (Files.exists(target)) return
        val source = LegacyFiles.mustRememberFile(storyDir)
        val content = if (source != null) LegacyFiles.toUserNote(Files.readString(source)) else {
            warnings += "memory/must_remember.md가 없어 빈 유저노트를 만든다"
            StoryFiles.USER_NOTE_INITIAL
        }
        AtomicFiles.writeString(target, content)
    }

    private fun writeChronicleIfMissing(storyDir: Path) {
        val target = MemoryDocs.chroniclePath(storyDir)
        if (Files.exists(target)) return
        val summaries = LegacyFiles.summaryFiles(storyDir).map { it.fileName.toString() to Files.readString(it) }
        AtomicFiles.writeString(target, LegacyFiles.chronicleFromSummaries(summaries))
    }

    // ---- 4. 메시지 ----

    /**
     * 옛 대화 파일을 DB 메시지로 가져온다. 가져온 수를 돌려준다.
     *
     * 턴 규칙(DESIGN.md §3)은 [MessageService]가 매긴다.
     * - USER: 새 턴
     * - USER 바로 뒤의 ASSISTANT: 같은 턴(NORMAL)
     * - ASSISTANT 뒤의 ASSISTANT: 이어쓰기(CONTINUATION, 새 턴)
     * - 맨 처음 메시지가 ASSISTANT: 프롤로그(턴 0)
     * 빈 메시지는 건너뛴다. ASSISTANT 첫 줄의 감정 태그는 떼어 emotion에 넣는다(§5.3).
     */
    private fun importMessages(storyId: Long, storyDir: Path, warnings: MutableList<String>): Int {
        if (messageRepository.findFirstByStoryIdOrderBySeqDesc(storyId) != null) {
            warnings += "DB에 이미 메시지가 있어 옛 대화를 가져오지 않았다"
            return 0
        }
        val files = LegacyFiles.chatFiles(storyDir)
        if (files.isEmpty()) {
            warnings += "옛 대화 파일이 없다"
            return 0
        }
        val parsed = files.flatMap { LegacyChatParser.parse(Files.readString(it)) }
        val empty = parsed.count { it.content.isBlank() }
        if (empty > 0) warnings += "빈 메시지 ${empty}개를 건너뛰었다"

        var tagOnly = 0
        val imported = tx.execute {
            var count = 0
            var prev: MessageRole? = null
            for (msg in parsed) {
                if (msg.content.isBlank()) continue
                when (msg.role) {
                    MessageRole.USER -> messageService.appendUser(storyId, msg.content)
                    MessageRole.ASSISTANT -> {
                        val split = LegacyChatParser.splitEmotion(msg.content)
                        if (split.content.isBlank()) {
                            tagOnly++
                            continue
                        }
                        val kind = when (prev) {
                            null -> MessageKind.PROLOGUE
                            MessageRole.USER -> MessageKind.NORMAL
                            MessageRole.ASSISTANT -> MessageKind.CONTINUATION
                        }
                        messageService.appendAssistant(storyId, split.content, ResponseTags.of(split.emotion), kind)
                    }
                }
                prev = msg.role
                count++
            }
            count
        } ?: 0
        if (tagOnly > 0) warnings += "감정 태그만 있는 응답 ${tagOnly}개를 건너뛰었다"
        return imported
    }

    // ---- 5. 옛 파일 보존 ----

    /** `chat/`, `memory/`를 `legacy/` 아래로 옮긴다. 같은 이름이 이미 있으면 시각을 붙인 이름으로 옮긴다. */
    private fun moveOldFilesToLegacy(storyDir: Path, warnings: MutableList<String>) {
        val legacyDir = storyDir.resolve(LegacyFiles.LEGACY_DIR)
        for (name in LegacyFiles.MOVED_DIRS) {
            val source = storyDir.resolve(name)
            if (!Files.exists(source)) continue
            Files.createDirectories(legacyDir)
            var target = legacyDir.resolve(name)
            if (Files.exists(target)) {
                target = legacyDir.resolve("$name-${System.currentTimeMillis()}")
                warnings += "legacy/$name 이 이미 있어 ${target.fileName}(으)로 옮겼다"
            }
            try {
                Files.move(source, target, StandardCopyOption.ATOMIC_MOVE)
            } catch (e: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(source, target)
            }
        }
    }

    private fun logReport(report: LegacyMigrationReport) {
        log.info("옛 스토리 이전 결과: 이전 {}, 건너뜀 {}, 실패 {}", report.migrated, report.skipped, report.failed)
        report.stories.filter { it.status != LegacyMigrationStatus.SKIPPED }.forEach { r ->
            log.info(
                "  [{}] storyId={} '{}' {}/{} → {}: 메시지 {}개, 턴 {}{}{}",
                r.status, r.storyId, r.title, r.scenarioName, r.fromDirName, r.dirName, r.importedMessages, r.turnCount,
                if (r.warnings.isEmpty()) "" else ", 경고: " + r.warnings.joinToString(" / "),
                r.error?.let { ", 오류: $it" } ?: "",
            )
        }
    }

    companion object {
        /** `_legacy` 이전 중인 새 폴더 표식. 내용은 스토리 ID. 이전이 끝나면 지운다. */
        const val LEGACY_MARKER = ".legacy-migration"
    }
}
