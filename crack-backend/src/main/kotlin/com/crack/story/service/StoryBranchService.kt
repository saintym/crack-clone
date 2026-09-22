package com.crack.story.service

import com.crack.global.config.DataPaths
import com.crack.global.exception.BadRequestException
import com.crack.global.exception.NotFoundException
import com.crack.message.entity.MessageVariant
import com.crack.message.entity.StoryMessage
import com.crack.message.repository.MessageVariantRepository
import com.crack.message.repository.StoryMessageRepository
import com.crack.story.dto.StoryResponse
import com.crack.story.entity.Story
import com.crack.story.files.StoryDirs
import com.crack.story.files.StoryFiles
import com.crack.story.files.StoryMeta
import com.crack.story.repository.StoryRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

/**
 * 스토리 분기 (T09, D4: 이식만 하고 확장하지 않는다).
 *
 * 1. 원본 스토리 폴더를 통째로 새 `stories/{밀리초}` 폴더로 복사한다.
 *    제외: `memory/history`(기록 스냅샷), `legacy/`(이전 전 옛 파일), `story.json`(새로 쓴다), `.`으로 시작하는 파일.
 * 2. 원본 메시지 중 기준 메시지의 seq까지를 후보(variants)와 함께 복사한다. seq·턴·종류·수정 시각은 그대로 둔다.
 * 3. `turn_count` = 복사한 메시지의 최대 턴.
 *
 * 4. `recorded_through_turn` = `min(원본값, 기준 메시지의 턴)` (T14). 기억 문서는 폴더째 복사되지만 `memory/history`와
 *    `memory_records`는 복사하지 않으므로, 분기 스토리에서는 분기 전 기록을 되돌릴 수 없다.
 *
 * 원본은 읽기만 한다. 폴더 복사나 DB 저장이 실패하면 만든 폴더를 지운다(DB는 트랜잭션 롤백).
 */
@Service
class StoryBranchService(
    private val storyDirs: StoryDirs,
    private val storyRepository: StoryRepository,
    private val messageRepository: StoryMessageRepository,
    private val variantRepository: MessageVariantRepository,
    private val dataPaths: DataPaths,
) {

    /**
     * [messageId] 또는 [messageIndex] 중 하나로 기준 메시지를 받는다.
     * [messageIndex]는 과도기용(T11이 프론트를 옮기기 전)이며 메시지의 `seq`로 해석한다.
     */
    @Transactional
    fun branch(storyId: Long, messageId: Long?, messageIndex: Int?, title: String): StoryResponse {
        if ((messageId == null) == (messageIndex == null)) {
            throw BadRequestException("messageId와 messageIndex 중 하나만 지정해야 합니다")
        }
        if (title.isBlank()) throw BadRequestException("분기 제목이 비어 있습니다")

        val source = storyDirs.locate(storyId)
        if (source.isLegacy || StoryFiles.readMeta(source.dir) == null) {
            throw BadRequestException("이전되지 않은 옛 스토리는 분기할 수 없습니다. 먼저 POST /api/admin/migrate-legacy로 이전하세요: $storyId")
        }

        val pivot = if (messageId != null) {
            messageRepository.findByIdAndStoryId(messageId, storyId)
                ?: throw NotFoundException("이 스토리의 메시지가 아닙니다: $messageId")
        } else {
            messageRepository.findByStoryIdOrderBySeqAsc(storyId).firstOrNull { it.seq == messageIndex }
                ?: throw BadRequestException("메시지 위치가 올바르지 않습니다: $messageIndex")
        }
        val copied = messageRepository.findByStoryIdOrderBySeqAsc(storyId).filter { it.seq <= pivot.seq }

        val scenarioName = source.scenario.name
        val dirName = newDirName(scenarioName)
        val targetDir = dataPaths.storyDir(scenarioName, dirName)
        val storiesRoot = dataPaths.scenarioDir(scenarioName).resolve(DataPaths.STORIES_DIR)

        // 폴더가 이미 있으면(다른 스토리) 여기서 실패하고, 아래 정리 대상이 되지 않는다
        Files.createDirectories(targetDir.parent)
        Files.createDirectory(targetDir)
        try {
            copyStoryDir(source.dir, targetDir)
            StoryMeta.create(scenarioName).write(targetDir.resolve(StoryFiles.STORY_JSON))

            val story = storyRepository.save(
                Story(
                    scenarioId = source.story.scenarioId,
                    title = title,
                    dirName = dirName,
                    turnCount = copied.maxOfOrNull { it.turnNo } ?: 0,
                    recordedThroughTurn = minOf(source.story.recordedThroughTurn, pivot.turnNo),
                )
            )
            copyMessages(copied, story.id)
            return StoryResponse.from(story)
        } catch (e: Exception) {
            try {
                StoryFiles.deleteStoryDir(storiesRoot, targetDir)
            } catch (cleanup: Exception) {
                e.addSuppressed(cleanup)
            }
            throw e
        }
    }

    private fun copyMessages(messages: List<StoryMessage>, newStoryId: Long) {
        if (messages.isEmpty()) return
        val variants = messages.associate { it.id to variantRepository.findByMessageIdOrderByVariantIndexAsc(it.id) }
        for (m in messages) {
            val copy = messageRepository.save(
                StoryMessage(
                    storyId = newStoryId,
                    seq = m.seq,
                    turnNo = m.turnNo,
                    role = m.role,
                    kind = m.kind,
                    content = m.content,
                    emotion = m.emotion,
                    selectedVariant = m.selectedVariant,
                    editedAt = m.editedAt,
                    createdAt = m.createdAt,
                )
            )
            variants[m.id].orEmpty().forEach { v ->
                variantRepository.save(
                    MessageVariant(
                        messageId = copy.id,
                        variantIndex = v.variantIndex,
                        content = v.content,
                        emotion = v.emotion,
                        instruction = v.instruction,
                        createdAt = v.createdAt,
                    )
                )
            }
        }
    }

    /** 스토리 폴더 내용을 이미 만든 빈 [targetDir]로 복사한다. */
    private fun copyStoryDir(sourceDir: Path, targetDir: Path) {
        Files.walk(sourceDir).use { stream ->
            stream.filter { it != sourceDir }.forEach { src ->
                val rel = sourceDir.relativize(src)
                if (isExcluded(rel)) return@forEach
                val dst = targetDir.resolve(rel.toString())
                when {
                    Files.isDirectory(src, LinkOption.NOFOLLOW_LINKS) -> Files.createDirectories(dst)
                    // 링크를 만들지 않고 내용을 복사한다(StoryFiles와 같은 규칙)
                    Files.isRegularFile(src) -> {
                        Files.createDirectories(dst.parent)
                        Files.copy(src, dst)
                    }
                }
            }
        }
    }

    private fun isExcluded(rel: Path): Boolean {
        val parts = rel.map { it.toString() }
        if (parts.any { it.startsWith(".") }) return true
        if (parts.first() == LEGACY_DIR) return true
        if (parts.size >= 2 && parts[0] == MEMORY_DIR && parts[1] == HISTORY_DIR) return true
        return parts.size == 1 && parts[0] == StoryFiles.STORY_JSON
    }

    /** 새 스토리 폴더 이름: 현재 시각 밀리초. 이미 있으면 1씩 올린다 (DESIGN.md §2). */
    private fun newDirName(scenarioName: String): String {
        var millis = System.currentTimeMillis()
        while (Files.exists(dataPaths.storyDir(scenarioName, millis.toString()))) millis++
        return millis.toString()
    }

    companion object {
        private const val LEGACY_DIR = "legacy"
        private const val MEMORY_DIR = "memory"
        private const val HISTORY_DIR = "history"
    }
}
