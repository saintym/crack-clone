package com.crack.story.service

import com.crack.chat.service.ChatFileService
import com.crack.global.config.DataPathConfig
import com.crack.global.exception.NotFoundException
import com.crack.scenario.repository.ScenarioRepository
import com.crack.story.dto.StoryCreateRequest
import com.crack.story.dto.StoryResponse
import com.crack.story.entity.Story
import com.crack.story.entity.StoryStatus
import com.crack.story.repository.StoryRepository
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
    private val dataPathConfig: DataPathConfig,
    private val chatFileService: ChatFileService
) {
    @Transactional
    fun create(scenarioId: Long, request: StoryCreateRequest): StoryResponse {
        val scenario = scenarioRepository.findById(scenarioId)
            .orElseThrow { NotFoundException("시나리오를 찾을 수 없습니다: $scenarioId") }

        val storyDataPath = Path.of(scenario.dataPath, "stories", System.currentTimeMillis().toString())
        initStoryDirectory(storyDataPath)

        val story = storyRepository.save(
            Story(
                scenarioId = scenarioId,
                title = request.title,
                dataPath = storyDataPath.toString()
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

        val storyDir = Path.of(story.dataPath)
        if (Files.exists(storyDir)) {
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
        val sourcePath = Path.of(sourceStory.dataPath)
        val allMessages = chatFileService.parseMessages(sourcePath)
        val branchMessages = allMessages.subList(0, minOf(messageIndex + 1, allMessages.size))

        // Create new story
        val storyDataPath = Path.of(scenario.dataPath, "stories", System.currentTimeMillis().toString())
        initStoryDirectory(storyDataPath)

        // Write branched messages
        chatFileService.writeMessages(storyDataPath, branchMessages)

        val turnCount = branchMessages.count { it.role == "assistant" }
        val story = storyRepository.save(
            Story(
                scenarioId = sourceStory.scenarioId,
                title = title,
                dataPath = storyDataPath.toString(),
                turnCount = turnCount
            )
        )
        return StoryResponse.from(story)
    }

    private fun initStoryDirectory(storyPath: Path) {
        Files.createDirectories(storyPath.resolve("characters"))
        Files.createDirectories(storyPath.resolve("memory"))
        Files.createDirectories(storyPath.resolve("chat/archive"))
        Files.writeString(storyPath.resolve("chat/chat_latest.md"), "# 최근 대화\n\n")
        Files.writeString(storyPath.resolve("memory/must_remember.md"), "# 필수 기억사항\n\n")
    }
}
