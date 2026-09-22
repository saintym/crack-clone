package com.crack.story.service

import com.crack.global.config.DataPaths
import com.crack.global.exception.NotFoundException
import com.crack.scenario.repository.ScenarioRepository
import com.crack.story.dto.StoryCreateRequest
import com.crack.story.dto.StoryResponse
import com.crack.story.entity.Story
import com.crack.story.entity.StoryStatus
import com.crack.story.files.StoryFiles
import com.crack.story.prologue.PrologueService
import com.crack.story.repository.StoryRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime

@Service
@Transactional(readOnly = true)
class StoryService(
    private val storyRepository: StoryRepository,
    private val scenarioRepository: ScenarioRepository,
    private val dataPaths: DataPaths,
    private val prologueService: PrologueService
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional
    fun create(scenarioId: Long, request: StoryCreateRequest): StoryResponse {
        val scenario = scenarioRepository.findById(scenarioId)
            .orElseThrow { NotFoundException("시나리오를 찾을 수 없습니다: $scenarioId") }

        val dirName = newDirName(scenario.name)
        val storyDir = createStoryDirectory(scenario.name, dirName)

        val story = withCleanupOnFailure(scenario.name, storyDir) {
            val saved = storyRepository.save(
                Story(
                    scenarioId = scenarioId,
                    title = request.title,
                    dirName = dirName
                )
            )
            // 첫 메시지(D16): 복사된 스토리 폴더의 prologue.md를 턴 0으로 넣는다. 턴 수는 0 그대로다.
            prologueService.insertIfPresent(saved.id, storyDir)
            saved
        }
        return StoryResponse.from(story)
    }

    fun findById(storyId: Long): StoryResponse {
        val story = storyRepository.findById(storyId)
            .orElseThrow { NotFoundException("스토리를 찾을 수 없습니다: $storyId") }
        return StoryResponse.from(story)
    }

    fun findByScenarioId(scenarioId: Long): List<StoryResponse> {
        return storyRepository.findByScenarioIdAndStatusOrderByUpdatedAtDesc(scenarioId)
            .map { StoryResponse.from(it) }
    }

    @Transactional
    fun archive(storyId: Long): StoryResponse {
        val story = storyRepository.findById(storyId)
            .orElseThrow { NotFoundException("스토리를 찾을 수 없습니다: $storyId") }
        story.status = StoryStatus.ARCHIVED
        story.updatedAt = LocalDateTime.now()
        return StoryResponse.from(story)
    }

    @Transactional
    fun delete(storyId: Long) {
        val story = storyRepository.findById(storyId)
            .orElseThrow { NotFoundException("스토리를 찾을 수 없습니다: $storyId") }

        val scenario = scenarioRepository.findById(story.scenarioId)
            .orElseThrow { NotFoundException("시나리오를 찾을 수 없습니다: ${story.scenarioId}") }

        val storyDir = dataPaths.storyDir(scenario.name, story.dirName)
        if (DataPaths.isLegacy(story.dirName)) {
            // _legacy 스토리의 폴더는 시나리오 폴더 자체다. 시나리오 원본까지 지우지 않도록 파일은 남긴다.
            log.warn("_legacy 스토리 삭제: 시나리오 폴더를 보존하고 DB 레코드만 삭제한다. storyId=$storyId, path=$storyDir")
        } else {
            // 스토리 폴더만 지운다. 시나리오 원본과 다른 스토리는 건드리지 않는다(D12).
            StoryFiles.deleteStoryDir(storiesRoot(scenario.name), storyDir)
        }

        storyRepository.delete(story)
    }

    // 분기는 StoryBranchService(T09)로 옮겼다. 스토리 폴더 복사 + DB 메시지 복사.

    /** 새 스토리 폴더 이름: 생성 시각 밀리초 (DESIGN.md §2). 같은 밀리초에 폴더가 이미 있으면 1씩 올린다. */
    private fun newDirName(scenarioName: String): String {
        var millis = System.currentTimeMillis()
        while (Files.exists(dataPaths.storyDir(scenarioName, millis.toString()))) {
            millis++
        }
        return millis.toString()
    }

    private fun storiesRoot(scenarioName: String): Path =
        dataPaths.scenarioDir(scenarioName).resolve(DataPaths.STORIES_DIR)

    /**
     * 시나리오 원본을 통째로 복사해 스토리 폴더를 만든다(D12). 실패하면 만들다 만 폴더를 지운다.
     * 폴더가 이미 있으면(다른 스토리의 폴더) 지우지 않고 그대로 실패한다.
     */
    private fun createStoryDirectory(scenarioName: String, dirName: String): Path {
        val storyDir = dataPaths.storyDir(scenarioName, dirName)
        try {
            StoryFiles.initFromScenario(dataPaths.scenarioDir(scenarioName), storyDir)
        } catch (e: FileAlreadyExistsException) {
            throw e
        } catch (e: Exception) {
            deleteQuietly(scenarioName, storyDir, e)
            throw e
        }
        return storyDir
    }

    /** [block]이 실패하면 새로 만든 스토리 폴더를 지우고 예외를 다시 던진다. 고아 폴더를 남기지 않기 위해서다. */
    private fun <T> withCleanupOnFailure(scenarioName: String, storyDir: Path, block: () -> T): T =
        try {
            block()
        } catch (e: Exception) {
            deleteQuietly(scenarioName, storyDir, e)
            throw e
        }

    private fun deleteQuietly(scenarioName: String, storyDir: Path, cause: Exception) {
        try {
            StoryFiles.deleteStoryDir(storiesRoot(scenarioName), storyDir)
        } catch (cleanup: Exception) {
            cause.addSuppressed(cleanup)
        }
    }
}
