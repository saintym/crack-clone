package com.crack.document.story

import com.crack.global.exception.BadRequestException
import com.crack.global.exception.NotFoundException
import com.crack.memory.docs.AtomicFiles
import com.crack.story.files.StoryDirs
import org.springframework.stereotype.Service
import java.nio.file.Files
import java.nio.file.Path

/**
 * 스토리 문서 API (DESIGN.md §9). 스토리 폴더 안의 화이트리스트 문서만 읽고 쓴다.
 * 시나리오 원본은 건드리지 않는다(D12). 원본 편집은 `DocumentService`(`/api/scenarios/{name}/documents`)가 한다.
 */
@Service
class StoryDocumentService(
    private val storyDirs: StoryDirs,
) {

    fun list(storyId: Long): List<StoryDocumentSummary> {
        val dir = existingStoryDir(storyId)
        val fixed = StoryDocumentPaths.FIXED.map { (path, kind) -> path to kind }
        val characters = listCharacterPaths(dir).map { it to StoryDocumentPaths.Kind.CHARACTERS }
        return (fixed + characters)
            .sortedWith(compareBy({ it.second.ordinal }, { it.first }))
            .mapNotNull { (path, kind) ->
                val file = dir.resolve(path)
                if (Files.isRegularFile(file)) StoryDocumentSummary(path, kind.value, Files.size(file)) else null
            }
    }

    fun read(storyId: Long, rawPath: String?): StoryDocumentContent {
        val doc = StoryDocumentPaths.parse(rawPath)
        val file = StoryDocumentPaths.resolve(existingStoryDir(storyId), doc)
        if (!Files.isRegularFile(file)) throw NotFoundException("문서를 찾을 수 없습니다: ${doc.path}")
        return StoryDocumentContent(doc.path, doc.kind.value, Files.readString(file))
    }

    /** 문서를 저장한다. 없던 문서(예: 새 인물)면 만든다. 원자적으로 쓴다. */
    fun write(storyId: Long, rawPath: String?, content: String): StoryDocumentContent {
        val doc = StoryDocumentPaths.parse(rawPath)
        val location = storyDirs.locate(storyId)
        if (location.isLegacy) {
            // _legacy 스토리의 폴더는 시나리오 원본이다. 여기서 쓰면 원본이 바뀌므로 T09 이전 전까지는 막는다.
            throw BadRequestException("옛 기본 스토리(_legacy)는 이전(T09) 전까지 스토리 문서를 수정할 수 없습니다")
        }
        val dir = requireExists(location.dir)
        val file = StoryDocumentPaths.resolve(dir, doc)
        AtomicFiles.writeString(file, content)
        return StoryDocumentContent(doc.path, doc.kind.value, content)
    }

    private fun existingStoryDir(storyId: Long): Path = requireExists(storyDirs.locate(storyId).dir)

    private fun requireExists(dir: Path): Path {
        if (!Files.isDirectory(dir)) throw NotFoundException("스토리 폴더가 없습니다")
        return dir
    }

    private fun listCharacterPaths(dir: Path): List<String> {
        val charDir = dir.resolve(StoryDocumentPaths.CHARACTERS_DIR)
        if (!Files.isDirectory(charDir)) return emptyList()
        return Files.list(charDir).use { stream ->
            stream.map { "${StoryDocumentPaths.CHARACTERS_DIR}/${it.fileName}" }
                .filter { it != StoryDocumentPaths.PROTAGONIST_PATH }
                .filter { runCatching { StoryDocumentPaths.parse(it) }.isSuccess }
                .sorted()
                .toList()
        }
    }
}
