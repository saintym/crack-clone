package com.crack.global.config

import com.crack.global.exception.BadRequestException
import org.springframework.stereotype.Component
import java.nio.file.Path

/**
 * 데이터 폴더 경로 계산의 유일한 진입점 (DESIGN.md §1, §2, BUG-005).
 *
 * DB에는 경로를 저장하지 않는다. 경로는 항상 `crack.data-path` + 시나리오 `name` + 스토리 `dir_name`으로 계산한다.
 * - 시나리오: `{root}/{scenarioName}`
 * - 스토리:   `{root}/{scenarioName}/stories/{dirName}`
 * - 옛 기본 스토리(`dir_name = '_legacy'`): 시나리오 폴더 자체. 폴더 이전은 T09가 한다.
 */
@Component
class DataPaths(config: DataPathConfig) {

    /** 데이터 루트. 상대 경로면 프로세스 작업 디렉터리 기준으로 한 번만 절대 경로로 바꾼다. */
    val root: Path = Path.of(config.dataPath).toAbsolutePath().normalize()

    fun scenarioDir(scenarioName: String): Path =
        root.resolve(requireSegment(scenarioName, "시나리오 이름"))

    fun storyDir(scenarioName: String, dirName: String): Path {
        val scenarioDir = scenarioDir(scenarioName)
        if (isLegacy(dirName)) return scenarioDir
        return scenarioDir.resolve(STORIES_DIR).resolve(requireSegment(dirName, "스토리 폴더 이름"))
    }

    fun templatesDir(): Path = root.resolve(TEMPLATES_DIR)

    /**
     * 경로 조각 하나만 허용한다. 구분자, `.`/`..`, 빈 문자열을 막아 데이터 루트 밖으로 나가지 못하게 한다.
     */
    private fun requireSegment(segment: String, label: String): String {
        if (segment.isBlank() || segment == "." || segment == ".." ||
            segment.contains('/') || segment.contains('\\') || segment.contains('\u0000')
        ) {
            throw BadRequestException("$label 이(가) 올바르지 않습니다: '$segment'")
        }
        return segment
    }

    companion object {
        /** V4 이전의 기본 스토리(시나리오 폴더를 그대로 쓰던 것)를 가리키는 dir_name. */
        const val LEGACY_DIR_NAME = "_legacy"
        const val STORIES_DIR = "stories"
        const val TEMPLATES_DIR = "_templates"

        fun isLegacy(dirName: String): Boolean = dirName == LEGACY_DIR_NAME
    }
}
