package com.crack.memory.docs

import java.nio.file.Files
import java.nio.file.Path

/**
 * 기억 문서 라이브러리의 진입점 (DESIGN.md §7.1 `MemoryDocs`).
 *
 * LLM 호출이 없는 순수 라이브러리다. 세부 기능은 [MarkdownSections], [CharacterDoc],
 * [ProtagonistDoc], [Chronicle], [StoryState], [MemoryBudgets], [AtomicFiles]에 있고,
 * 여기서는 자주 쓰는 함수와 스토리 폴더 기준 경로 규칙을 모아 둔다.
 */
object MemoryDocs {

    const val CHARACTERS_DIR = "characters"
    const val CHRONICLE_FILE = "chronicle.md"
    const val STATE_FILE = "state.json"

    private val FIELD_SEPARATORS = Regex("""\s*[,，、]\s*""")

    fun readSection(text: String, title: String, level: Int = 2): String? =
        MarkdownSections.readSection(text, title, level)

    fun replaceSection(text: String, title: String, newBody: String, level: Int = 2): String =
        MarkdownSections.replaceSection(text, title, newBody, level)

    /**
     * `- **이름**: 값` 형태의 목록 필드 값. 첫 번째로 나오는 줄을 쓴다. 코드 펜스 안은 보지 않는다.
     * 줄이 없으면 null, 값이 비어 있으면 빈 문자열.
     */
    fun parseField(text: String, field: String): String? {
        val pattern = Regex("""^\s{0,3}[-*+]\s*\*\*${Regex.escape(field)}\*\*\s*[:：]?(.*)$""")
        var inFence = false
        for (raw in text.lineSequence()) {
            val line = raw.removeSuffix("\r")
            if (line.trimStart().startsWith("```") || line.trimStart().startsWith("~~~")) {
                inFence = !inFence
                continue
            }
            if (inFence) continue
            val m = pattern.find(line) ?: continue
            return m.groupValues[1].trim()
        }
        return null
    }

    /**
     * `- **별칭**: 월아, 설 소저` 줄의 별칭 목록. 쉼표(`,` `，` `、`)로 나눈다.
     * 괄호로 감싼 안내 문구(`(없으면 비워 둔다)`)와 빈 값은 버린다. 중복은 한 번만 남긴다.
     */
    fun parseAliases(text: String): List<String> {
        val value = parseField(text, "별칭") ?: return emptyList()
        if (value.startsWith("(") && value.endsWith(")")) return emptyList() // 템플릿 안내 문구
        return value.split(FIELD_SEPARATORS)
            .map { it.trim() }
            .filter { it.isNotEmpty() && !(it.startsWith("(") && it.endsWith(")")) }
            .distinct()
    }

    // --- 스토리 폴더 기준 경로와 입출력 ---

    fun characterPath(storyDir: Path, name: String): Path = storyDir.resolve(CHARACTERS_DIR).resolve("$name.md")
    fun protagonistPath(storyDir: Path): Path = storyDir.resolve(CHARACTERS_DIR).resolve(ProtagonistDoc.FILE_NAME)
    fun chroniclePath(storyDir: Path): Path = storyDir.resolve(CHRONICLE_FILE)
    fun statePath(storyDir: Path): Path = storyDir.resolve(STATE_FILE)

    /** `characters/` 아래 인물 문서 전부(주인공 제외), 이름순. 폴더가 없으면 빈 목록. */
    fun readCharacters(storyDir: Path): List<CharacterDoc> {
        val dir = storyDir.resolve(CHARACTERS_DIR)
        if (!Files.isDirectory(dir)) return emptyList()
        return Files.list(dir).use { stream ->
            stream.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".md") }
                .filter { it.fileName.toString() != ProtagonistDoc.FILE_NAME }
                .sorted()
                .map { CharacterDoc.read(it) }
                .toList()
        }
    }

    fun readProtagonist(storyDir: Path): ProtagonistDoc? =
        protagonistPath(storyDir).takeIf { Files.isRegularFile(it) }?.let { ProtagonistDoc.read(it) }

    /** 연대기를 읽는다. 파일이 없으면 빈 연대기. */
    fun readChronicle(storyDir: Path): Chronicle = Chronicle.read(chroniclePath(storyDir))

    /** `state.json`을 읽는다. 파일이 없으면 빈 기본값. */
    fun readState(storyDir: Path): StoryState = StoryState.read(statePath(storyDir))

    fun writeState(storyDir: Path, state: StoryState) = state.write(statePath(storyDir))
}
