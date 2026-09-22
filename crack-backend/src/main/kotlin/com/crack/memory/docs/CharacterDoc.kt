package com.crack.memory.docs

import java.nio.file.Files
import java.nio.file.Path

/** 인물 `## 기억` 섹션을 구조체로 읽은 결과. */
data class CharacterMemory(
    val relations: List<RelationEntry> = emptyList(),
    val events: List<EventEntry> = emptyList(),
    /** `### 소지품·기술·신체` */
    val possessions: List<MemoryItem> = emptyList(),
) {
    fun isEmpty() = relations.isEmpty() && events.isEmpty() && possessions.isEmpty()

    /** `## 기억` 섹션 본문(제목 줄 제외) 마크다운. */
    fun toMarkdown(): String = buildString {
        append("### ").append(CharacterDoc.RELATIONS).append('\n')
        relations.forEach { append(it.item.toMarkdown()).append('\n') }
        append("### ").append(CharacterDoc.EVENTS).append('\n')
        events.forEach { append(it.item.toMarkdown()).append('\n') }
        append("### ").append(CharacterDoc.POSSESSIONS).append('\n')
        possessions.forEach { append(it.toMarkdown()).append('\n') }
    }
}

/**
 * 인물 문서 `characters/{이름}.md` (DESIGN.md §7.1).
 *
 * 원본 설정 부분은 절대 바꾸지 않고 `## 기억` 섹션만 교체한다. 불변 객체이며,
 * 변경 메서드는 새 인스턴스를 돌려준다.
 *
 * @property name 인물 이름. 파일명에서 `.md`를 뗀 값이다
 * @property text 문서 원문
 */
class CharacterDoc(val name: String, val text: String) {

    /** `- **별칭**: a, b` 줄의 별칭 목록. 줄이 없거나 비어 있으면 빈 목록. */
    fun parseAliases(): List<String> = MemoryDocs.parseAliases(text)

    /** `## 기억` 섹션 본문 원문(제목 줄 제외). 섹션이 없으면 null. */
    val memorySection: String? get() = MarkdownSections.readSection(text, MEMORY)

    /** `## 기억` 섹션 본문의 글자 수(앞뒤 공백 제외). 예산 검사에 쓴다. */
    fun memoryLength(): Int = memorySection?.trim()?.length ?: 0

    /** `## 기억`의 하위 `###` 섹션을 구조체로 읽는다. */
    fun memory(): CharacterMemory {
        val body = memorySection ?: return CharacterMemory()
        return CharacterMemory(
            relations = MemoryEntries.parseRelations(subsection(body, RELATIONS)),
            events = MemoryEntries.parseEvents(subsection(body, EVENTS)),
            possessions = MemoryEntries.parseItems(subsection(body, POSSESSIONS)),
        )
    }

    /** `## 기억` 섹션 본문을 [newBody]로 통째로 교체한 새 문서. 섹션이 없으면 끝에 추가한다. */
    fun withMemorySection(newBody: String): CharacterDoc =
        CharacterDoc(name, MarkdownSections.replaceSection(text, MEMORY, newBody))

    fun withMemory(memory: CharacterMemory): CharacterDoc = withMemorySection(memory.toMarkdown())

    /** 원자적으로 파일에 쓴다. */
    fun write(path: Path) = AtomicFiles.writeString(path, text)

    override fun equals(other: Any?) = other is CharacterDoc && other.name == name && other.text == text
    override fun hashCode() = 31 * name.hashCode() + text.hashCode()
    override fun toString() = "CharacterDoc(name=$name, length=${text.length})"

    companion object {
        const val MEMORY = "기억"
        const val RELATIONS = "관계"
        const val EVENTS = "사건"
        const val POSSESSIONS = "소지품·기술·신체"

        fun parse(name: String, text: String) = CharacterDoc(name, text)

        /** 파일을 읽는다. 이름은 파일명에서 `.md`를 뗀 값이다. */
        fun read(path: Path): CharacterDoc =
            CharacterDoc(path.fileName.toString().removeSuffix(".md"), Files.readString(path))
    }
}
