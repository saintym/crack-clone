package com.crack.memory.docs

import java.nio.file.Files
import java.nio.file.Path

/**
 * 연대기 회차 하나. `## 회차 3 (턴 21–30)` + 본문.
 *
 * @property body 제목 줄을 뺀 본문(앞뒤 빈 줄 제거)
 */
data class ChronicleEntry(val number: Int, val fromTurn: Int, val toTurn: Int, val body: String) {
    init {
        require(number > 0) { "회차 번호는 1 이상이어야 한다: $number" }
        require(fromTurn <= toTurn) { "턴 범위가 잘못됐다: $fromTurn–$toTurn" }
    }

    val title: String get() = "회차 $number (턴 $fromTurn–$toTurn)"

    fun toMarkdown(): String = MarkdownSections.renderSection(2, title, body)
}

/** [Chronicle.split] 결과. */
data class ChronicleParts(val summary: String?, val entries: List<ChronicleEntry>)

/**
 * 스토리 폴더의 `chronicle.md` (DESIGN.md §7.1).
 *
 * ```markdown
 * # 연대기
 * ## 장 요약
 * (오래된 회차를 압축한 요약. 없으면 섹션 생략)
 * ## 회차 3 (턴 21–30)
 * - ...
 * ```
 *
 * 원문 텍스트를 들고 있는 불변 객체다. 변경 메서드는 새 인스턴스를 돌려주며,
 * 건드리지 않은 부분(제목, 알 수 없는 섹션 등)은 원문 그대로 보존한다.
 */
class Chronicle(val text: String) {

    private fun entrySections(): List<Pair<MarkdownSections.Section, ChronicleEntry>> =
        MarkdownSections.sections(text, 2).mapNotNull { s ->
            val m = ENTRY_TITLE.matchEntire(s.title.trim()) ?: return@mapNotNull null
            val from = m.groupValues[2].toInt()
            val to = m.groupValues[3].toIntOrNull() ?: from
            if (from > to) return@mapNotNull null
            s to ChronicleEntry(m.groupValues[1].toInt(), from, to, s.body(text).trim())
        }

    /** `## 장 요약` 본문(앞뒤 공백 제거). 섹션이 없거나 비어 있으면 null. */
    val summary: String? get() = MarkdownSections.readSection(text, SUMMARY)?.trim()?.takeIf { it.isNotEmpty() }

    /** 회차 목록. 파일에 적힌 순서(오래된 것부터)다. */
    fun entries(): List<ChronicleEntry> = entrySections().map { it.second }

    fun split(): ChronicleParts = ChronicleParts(summary, entries())

    /** 다음 회차 번호. 회차가 없으면 1. */
    fun nextNumber(): Int = (entries().maxOfOrNull { it.number } ?: 0) + 1

    /** 마지막 회차의 끝 턴. 회차가 없으면 null. */
    fun lastTurn(): Int? = entries().maxOfOrNull { it.toTurn }

    /**
     * 회차 원문의 글자 수. 각 회차 섹션(제목 줄 포함)의 앞뒤 공백을 뺀 길이의 합이다.
     * `## 장 요약`과 문서 제목은 세지 않는다. 예산(`crack.memory.budget.chronicle`) 검사에 쓴다.
     */
    fun rawLength(): Int = entrySections().sumOf { it.first.full(text).trim().length }

    /** 가장 오래된 회차 [n]개. 회차가 그보다 적으면 있는 만큼. */
    fun oldestEntries(n: Int): List<ChronicleEntry> {
        require(n >= 0) { "n은 0 이상이어야 한다: $n" }
        return entries().take(n)
    }

    /** 가장 최근 회차 [n]개(오래된 것부터). 관리자 프롬프트의 "연대기 최근 부분"용. */
    fun latestEntries(n: Int): List<ChronicleEntry> {
        require(n >= 0) { "n은 0 이상이어야 한다: $n" }
        return entries().takeLast(n)
    }

    /** 회차를 문서 끝에 추가한 새 연대기. 문서가 비어 있으면 `# 연대기` 제목부터 만든다. */
    fun append(entry: ChronicleEntry): Chronicle {
        val base = if (text.isBlank()) EMPTY_TEXT else text
        return Chronicle(MarkdownSections.appendSection(base, entry.title, entry.body))
    }

    /**
     * 가장 오래된 회차 [n]개를 지우고 `## 장 요약`을 [summary]로 바꾼 새 연대기.
     *
     * - [summary]는 **새 장 요약 전체**다(기존 요약 + 지운 회차를 합친 결과). 기존 요약에 덧붙이지 않는다.
     * - [summary]가 비어 있으면 `## 장 요약` 섹션을 없앤다.
     * - `## 장 요약`이 없으면 첫 `##` 섹션 앞(문서 제목 바로 뒤)에 새로 만든다.
     */
    fun replaceOldestWithSummary(n: Int, summary: String): Chronicle {
        require(n >= 0) { "n은 0 이상이어야 한다: $n" }
        var result = text
        // 뒤에서부터 지워야 앞 섹션의 오프셋이 유지된다
        entrySections().take(n).asReversed().forEach { (section, _) ->
            result = MarkdownSections.removeSection(result, section)
        }
        return Chronicle(setSummary(result, summary))
    }

    /** [replaceOldestWithSummary]의 별칭 (DESIGN.md §7.1 `compactOldest`). */
    fun compactOldest(n: Int, summary: String): Chronicle = replaceOldestWithSummary(n, summary)

    fun write(path: Path) = AtomicFiles.writeString(path, text)

    override fun equals(other: Any?) = other is Chronicle && other.text == text
    override fun hashCode() = text.hashCode()
    override fun toString() = "Chronicle(entries=${entries().size}, rawLength=${rawLength()})"

    companion object {
        const val SUMMARY = "장 요약"
        const val EMPTY_TEXT = "# 연대기\n"

        private val ENTRY_TITLE = Regex("""회차\s*(\d+)\s*\(\s*턴\s*(\d+)(?:\s*[–—~-]\s*(\d+))?\s*\)""")

        fun parse(text: String) = Chronicle(text)

        fun empty() = Chronicle(EMPTY_TEXT)

        /** 파일을 읽는다. 없으면 빈 연대기(`# 연대기`). */
        fun read(path: Path): Chronicle =
            if (Files.isRegularFile(path)) Chronicle(Files.readString(path)) else empty()

        private fun setSummary(text: String, summary: String): String {
            val existing = MarkdownSections.find(text, SUMMARY)
            if (summary.isBlank()) {
                return if (existing == null) text else MarkdownSections.removeSection(text, existing)
            }
            if (existing != null) return MarkdownSections.replaceSection(text, SUMMARY, summary)

            val sep = MarkdownSections.lineSeparator(text)
            val first = MarkdownSections.sections(text, 2).firstOrNull()
                ?: return MarkdownSections.appendSection(text.ifBlank { EMPTY_TEXT }, SUMMARY, summary)
            val block = MarkdownSections.renderSection(2, SUMMARY, summary, sep) + sep
            return text.substring(0, first.headingStart) + block + text.substring(first.headingStart)
        }
    }
}
