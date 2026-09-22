package com.crack.image

import com.crack.global.config.DataPaths
import com.crack.story.files.StoryDirs
import org.springframework.stereotype.Service
import java.nio.file.Files
import java.nio.file.Path

/**
 * 스토리가 쓰는 이미지 카탈로그 (DESIGN.md §2, §8.5).
 *
 * `images.md`는 스토리에 복사하지 않고 **시나리오 원본**을 참조한다. 읽기만 하고 쓰지 않는다.
 * 편집은 시나리오 문서 API(`/api/scenarios/{name}/documents/images`)가 맡는다.
 */
@Service
class ImageCatalogService(
    private val storyDirs: StoryDirs,
    private val dataPaths: DataPaths,
) {
    /** 스토리가 없으면 [com.crack.global.exception.NotFoundException]. `images.md`가 없으면 빈 목록. */
    fun forStory(storyId: Long): List<ImageEntry> {
        val scenario = storyDirs.locate(storyId).scenario
        return load(dataPaths.scenarioDir(scenario.name))
    }

    companion object {
        const val FILE_NAME = "images.md"

        /** 시나리오 폴더의 `images.md`를 읽는다. 없으면 빈 목록. */
        fun load(scenarioDir: Path): List<ImageEntry> {
            val file = scenarioDir.resolve(FILE_NAME)
            if (!Files.isRegularFile(file)) return emptyList()
            return ImageCatalogParser.parse(Files.readString(file))
        }
    }
}
