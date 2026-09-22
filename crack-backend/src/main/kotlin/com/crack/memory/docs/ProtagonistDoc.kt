package com.crack.memory.docs

import java.nio.file.Files
import java.nio.file.Path

/** 주인공 `## 변화 기록` 섹션을 구조체로 읽은 결과. */
data class ProtagonistChanges(
    /** `### 관계` (인물별) */
    val relations: List<RelationEntry> = emptyList(),
    /** `### 스탯·기술` */
    val statsAndSkills: List<MemoryItem> = emptyList(),
    /** `### 소지품` */
    val possessions: List<MemoryItem> = emptyList(),
    /** `### 신체` */
    val body: List<MemoryItem> = emptyList(),
) {
    fun isEmpty() = relations.isEmpty() && statsAndSkills.isEmpty() && possessions.isEmpty() && body.isEmpty()

    /** `## 변화 기록` 섹션 본문(제목 줄 제외) 마크다운. */
    fun toMarkdown(): String = buildString {
        append("### ").append(ProtagonistDoc.RELATIONS).append('\n')
        relations.forEach { append(it.item.toMarkdown()).append('\n') }
        append("### ").append(ProtagonistDoc.STATS_SKILLS).append('\n')
        statsAndSkills.forEach { append(it.toMarkdown()).append('\n') }
        append("### ").append(ProtagonistDoc.POSSESSIONS).append('\n')
        possessions.forEach { append(it.toMarkdown()).append('\n') }
        append("### ").append(ProtagonistDoc.BODY).append('\n')
        body.forEach { append(it.toMarkdown()).append('\n') }
    }
}

/**
 * 주인공 문서 `characters/protagonist.md` (DESIGN.md §7.1).
 *
 * `## 변화 기록` 섹션만 교체한다. 불변 객체이며, 변경 메서드는 새 인스턴스를 돌려준다.
 */
class ProtagonistDoc(val text: String) {

    /** `- **이름**: …` 줄의 값. 없거나 비어 있으면 null. */
    fun displayName(): String? = MemoryDocs.parseField(text, "이름")?.takeIf { it.isNotBlank() }

    fun parseAliases(): List<String> = MemoryDocs.parseAliases(text)

    /** `## 변화 기록` 섹션 본문 원문(제목 줄 제외). 섹션이 없으면 null. */
    val changesSection: String? get() = MarkdownSections.readSection(text, CHANGES)

    /** `## 변화 기록` 섹션 본문의 글자 수(앞뒤 공백 제외). 예산 검사에 쓴다. */
    fun changesLength(): Int = changesSection?.trim()?.length ?: 0

    fun changes(): ProtagonistChanges {
        val section = changesSection ?: return ProtagonistChanges()
        return ProtagonistChanges(
            relations = MemoryEntries.parseRelations(subsection(section, RELATIONS)),
            statsAndSkills = MemoryEntries.parseItems(subsection(section, STATS_SKILLS)),
            possessions = MemoryEntries.parseItems(subsection(section, POSSESSIONS)),
            body = MemoryEntries.parseItems(subsection(section, BODY)),
        )
    }

    /** `## 변화 기록` 섹션 본문을 통째로 교체한 새 문서. 섹션이 없으면 끝에 추가한다. */
    fun withChangesSection(newBody: String): ProtagonistDoc =
        ProtagonistDoc(MarkdownSections.replaceSection(text, CHANGES, newBody))

    fun withChanges(changes: ProtagonistChanges): ProtagonistDoc = withChangesSection(changes.toMarkdown())

    fun write(path: Path) = AtomicFiles.writeString(path, text)

    override fun equals(other: Any?) = other is ProtagonistDoc && other.text == text
    override fun hashCode() = text.hashCode()
    override fun toString() = "ProtagonistDoc(length=${text.length})"

    companion object {
        const val FILE_NAME = "protagonist.md"
        const val CHANGES = "변화 기록"
        const val RELATIONS = "관계"
        const val STATS_SKILLS = "스탯·기술"
        const val POSSESSIONS = "소지품"
        const val BODY = "신체"

        fun parse(text: String) = ProtagonistDoc(text)
        fun read(path: Path) = ProtagonistDoc(Files.readString(path))
    }
}
