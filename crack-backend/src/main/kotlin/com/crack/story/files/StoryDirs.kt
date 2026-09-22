package com.crack.story.files

import com.crack.global.config.DataPaths
import com.crack.global.exception.NotFoundException
import com.crack.scenario.entity.Scenario
import com.crack.scenario.repository.ScenarioRepository
import com.crack.story.entity.Story
import com.crack.story.repository.StoryRepository
import org.springframework.stereotype.Component
import java.nio.file.Path

/** 스토리 ID로 스토리 폴더 위치를 찾는다. 경로 계산은 [DataPaths]에 맡긴다. */
@Component
class StoryDirs(
    private val storyRepository: StoryRepository,
    private val scenarioRepository: ScenarioRepository,
    private val dataPaths: DataPaths,
) {
    data class Location(val story: Story, val scenario: Scenario, val dir: Path) {
        /** `_legacy` 스토리는 폴더가 시나리오 원본 폴더 자체다(T09 이전 전까지). */
        val isLegacy: Boolean get() = DataPaths.isLegacy(story.dirName)
    }

    fun locate(storyId: Long): Location {
        val story = storyRepository.findById(storyId)
            .orElseThrow { NotFoundException("스토리를 찾을 수 없습니다: $storyId") }
        val scenario = scenarioRepository.findById(story.scenarioId)
            .orElseThrow { NotFoundException("시나리오를 찾을 수 없습니다: ${story.scenarioId}") }
        return Location(story, scenario, dataPaths.storyDir(scenario.name, story.dirName))
    }
}
