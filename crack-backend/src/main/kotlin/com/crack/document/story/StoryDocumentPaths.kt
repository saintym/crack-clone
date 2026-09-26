package com.crack.document.story

import com.crack.global.exception.BadRequestException
import com.crack.story.settings.StorySettings
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

/**
 * 스토리 문서 API의 경로 화이트리스트 (DESIGN.md §9).
 *
 * 허용: `world.md`, `scenario.md`, `prologue.md`, `characters/{이름}.md`(주인공 `characters/protagonist.md` 포함),
 * `chronicle.md`, `user_note.md`, `keywords.md`, `commands.md`, `settings.json`(§6.4).
 * `..`, `.`, 절대 경로, 역슬래시, 빈 조각, 숨김 파일, 그 밖의 모든 경로는 거부한다.
 */
object StoryDocumentPaths {

    /** 문서 종류. 목록 응답의 `kind`와 정렬 순서를 정한다. */
    enum class Kind(val value: String) {
        WORLD("world"),
        SCENARIO("scenario"),
        PROLOGUE("prologue"),
        PROTAGONIST("protagonist"),
        CHARACTERS("characters"),
        CHRONICLE("chronicle"),
        USER_NOTE("user_note"),
        KEYWORDS("keywords"),
        COMMANDS("commands"),

        /** `settings.json` (§6.4 응답 분량). 유일한 비마크다운 문서다. */
        SETTINGS("settings"),
    }

    const val CHARACTERS_DIR = "characters"
    const val PROTAGONIST_PATH = "characters/protagonist.md"

    /** 인물 문서 외의 고정 경로. 순서가 목록 순서다. */
    val FIXED: Map<String, Kind> = linkedMapOf(
        "world.md" to Kind.WORLD,
        "scenario.md" to Kind.SCENARIO,
        "prologue.md" to Kind.PROLOGUE,
        PROTAGONIST_PATH to Kind.PROTAGONIST,
        "chronicle.md" to Kind.CHRONICLE,
        "user_note.md" to Kind.USER_NOTE,
        "keywords.md" to Kind.KEYWORDS,
        "commands.md" to Kind.COMMANDS,
        StorySettings.FILE_NAME to Kind.SETTINGS,
    )

    data class DocPath(val path: String, val kind: Kind)

    /** 경로 문자열을 검사한다. 화이트리스트 밖이면 [BadRequestException]. */
    fun parse(raw: String?): DocPath {
        val path = raw ?: throw BadRequestException("path가 필요합니다")
        if (path.isBlank() || path.contains('\u0000') || path.contains('\\') || path.contains(':') ||
            path.startsWith("/") || path.startsWith("~")
        ) {
            throw invalid(path)
        }
        val segments = path.split('/')
        if (segments.any { it.isEmpty() || it == "." || it == ".." || it.startsWith(".") || it.isBlank() }) {
            throw invalid(path)
        }
        FIXED[path]?.let { return DocPath(path, it) }
        if (segments.size == 2 && segments[0] == CHARACTERS_DIR && segments[1].endsWith(".md") &&
            segments[1].length > ".md".length
        ) {
            return DocPath(path, Kind.CHARACTERS)
        }
        throw invalid(path)
    }

    /**
     * 검사한 경로를 스토리 폴더 기준 실제 경로로 바꾼다.
     * 정규화한 경로와 (있다면) 심볼릭 링크를 풀어낸 실제 경로가 모두 스토리 폴더 안이어야 한다.
     */
    fun resolve(storyDir: Path, doc: DocPath): Path {
        val base = storyDir.toAbsolutePath().normalize()
        val target = base.resolve(doc.path).normalize()
        if (!target.startsWith(base) || target == base) throw invalid(doc.path)
        if (Files.exists(base)) {
            val realBase = base.toRealPath()
            // 파일이 있으면 파일, 없으면 가장 가까운 상위 폴더의 실제 위치를 확인한다
            var probe: Path? = target
            while (probe != null && !Files.exists(probe, LinkOption.NOFOLLOW_LINKS)) probe = probe.parent
            if (probe != null) {
                // 끊어진 심볼릭 링크(Files.exists가 거짓)도 거부한다
                if (!Files.exists(probe) || !probe.toRealPath().startsWith(realBase)) throw invalid(doc.path)
            }
        }
        return target
    }

    private fun invalid(path: String) = BadRequestException(
        "허용되지 않는 문서 경로입니다: '$path' " +
            "(가능: ${FIXED.keys.joinToString()}, characters/{이름}.md)"
    )
}
