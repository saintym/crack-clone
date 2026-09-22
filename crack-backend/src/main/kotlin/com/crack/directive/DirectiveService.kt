package com.crack.directive

import com.crack.global.exception.BadRequestException
import com.crack.global.exception.NotFoundException
import com.crack.story.files.StoryDirs
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/**
 * 지속 OOC 지시 CRUD (DESIGN.md §8.1, D10). 스토리 폴더의 `directives.json`만 읽고 쓴다(D12).
 *
 * - 쓰기는 스토리별로 직렬화한다(읽고-고치고-쓰는 사이에 다른 요청이 끼면 한쪽 변경이 사라진다).
 * - `_legacy` 스토리는 폴더가 시나리오 원본이라 쓰기를 400으로 막는다(스토리 문서 API와 같다).
 */
@Service
class DirectiveService(
    private val storyDirs: StoryDirs,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val locks = ConcurrentHashMap<Long, Any>()

    fun list(storyId: Long): List<Directive> = DirectiveFile.read(storyDirs.locate(storyId).dir)

    fun add(storyId: Long, text: String?, enabled: Boolean? = null): Directive {
        val trimmed = requireText(text)
        return modify(storyId) { current ->
            val created = Directive.create(trimmed, enabled ?: true)
            (current + created) to created
        }
    }

    /** [text]와 [enabled] 중 준 것만 바꾼다. */
    fun update(storyId: Long, id: String, text: String?, enabled: Boolean?): Directive {
        val trimmed = text?.let { requireText(it) }
        return modify(storyId) { current ->
            val index = current.indexOfFirst { it.id == id }
            if (index < 0) throw NotFoundException("지시를 찾을 수 없습니다: $id")
            val updated = current[index].copy(
                text = trimmed ?: current[index].text,
                enabled = enabled ?: current[index].enabled,
            )
            current.toMutableList().also { it[index] = updated } to updated
        }
    }

    fun delete(storyId: Long, id: String) {
        modify(storyId) { current ->
            if (current.none { it.id == id }) throw NotFoundException("지시를 찾을 수 없습니다: $id")
            current.filterNot { it.id == id } to Unit
        }
    }

    /**
     * 프롬프트 주입용: [storyDir]의 켜진 지시. **예외를 던지지 않는다.** 파일이 깨져 있으면 로그만 남기고 빈 목록.
     */
    fun enabledIn(storyDir: Path): List<Directive> =
        try {
            DirectiveFile.read(storyDir).filter { it.enabled && it.text.isNotBlank() }
        } catch (e: Exception) {
            log.warn("지시 파일을 읽지 못해 주입을 건너뜁니다: {} ({})", storyDir, e.message)
            emptyList()
        }

    private fun <T> modify(storyId: Long, change: (List<Directive>) -> Pair<List<Directive>, T>): T {
        val location = storyDirs.locate(storyId)
        if (location.isLegacy) {
            throw BadRequestException("옛 기본 스토리(_legacy)는 이전(T09) 전까지 지시를 수정할 수 없습니다")
        }
        synchronized(locks.computeIfAbsent(storyId) { Any() }) {
            val (next, result) = change(DirectiveFile.read(location.dir))
            DirectiveFile.write(location.dir, next)
            return result
        }
    }

    private fun requireText(text: String?): String =
        text?.trim()?.takeIf { it.isNotEmpty() } ?: throw BadRequestException("지시 내용이 비어 있습니다")
}
