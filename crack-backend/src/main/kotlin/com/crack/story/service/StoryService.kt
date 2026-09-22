package com.crack.story.service

import com.crack.chat.service.ChatFileService
import com.crack.global.config.DataPaths
import com.crack.global.exception.NotFoundException
import com.crack.scenario.repository.ScenarioRepository
import com.crack.story.dto.StoryCreateRequest
import com.crack.story.dto.StoryResponse
import com.crack.story.entity.Story
import com.crack.story.entity.StoryStatus
import com.crack.story.repository.StoryRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime

@Service
@Transactional(readOnly = true)
class StoryService(
    private val storyRepository: StoryRepository,
    private val scenarioRepository: ScenarioRepository,
    private val dataPaths: DataPaths,
    private val chatFileService: ChatFileService
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional
    fun create(scenarioId: Long, request: StoryCreateRequest): StoryResponse {
        val scenario = scenarioRepository.findById(scenarioId)
            .orElseThrow { NotFoundException("시나리오를 찾을 수 없습니다: $scenarioId") }

        val dirName = newDirName(scenario.name)
        initStoryDirectory(dataPaths.storyDir(scenario.name, dirName))

        val story = storyRepository.save(
            Story(
                scenarioId = scenarioId,
                title = request.title,
                dirName = dirName
            )
        )
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
        } else if (Files.exists(storyDir)) {
            Files.walk(storyDir)
                .sorted(Comparator.reverseOrder())
                .forEach { Files.deleteIfExists(it) }
        }

        storyRepository.delete(story)
    }

    @Transactional
    fun branch(storyId: Long, messageIndex: Int, title: String): StoryResponse {
        val sourceStory = storyRepository.findById(storyId)
            .orElseThrow { NotFoundException("스토리를 찾을 수 없습니다: $storyId") }

        val scenario = scenarioRepository.findById(sourceStory.scenarioId)
            .orElseThrow { NotFoundException("시나리오를 찾을 수 없습니다: ${sourceStory.scenarioId}") }

        // Parse source messages and take up to messageIndex (inclusive)
        val sourcePath = dataPaths.storyDir(scenario.name, sourceStory.dirName)
        val allMessages = chatFileService.parseMessages(sourcePath)
        val branchMessages = allMessages.subList(0, minOf(messageIndex + 1, allMessages.size))

        // Create new story
        val dirName = newDirName(scenario.name)
        val storyDataPath = dataPaths.storyDir(scenario.name, dirName)
        initStoryDirectory(storyDataPath)

        // Write branched messages
        chatFileService.writeMessages(storyDataPath, branchMessages)

        val turnCount = branchMessages.count { it.role == "assistant" }
        val story = storyRepository.save(
            Story(
                scenarioId = sourceStory.scenarioId,
                title = title,
                dirName = dirName,
                turnCount = turnCount
            )
        )
        return StoryResponse.from(story)
    }

    /** 새 스토리 폴더 이름: 생성 시각 밀리초 (DESIGN.md §2). 같은 밀리초에 폴더가 이미 있으면 1씩 올린다. */
    private fun newDirName(scenarioName: String): String {
        var millis = System.currentTimeMillis()
        while (Files.exists(dataPaths.storyDir(scenarioName, millis.toString()))) {
            millis++
        }
        return millis.toString()
    }

    private fun initStoryDirectory(storyPath: Path) {
        Files.createDirectories(storyPath.resolve("characters"))
        Files.createDirectories(storyPath.resolve("memory"))
        Files.createDirectories(storyPath.resolve("chat/archive"))
        Files.writeString(storyPath.resolve("chat/chat_latest.md"), "# 최근 대화\n\n")
        Files.writeString(storyPath.resolve("memory/must_remember.md"), "# 필수 기억사항\n\n")
    }
}
