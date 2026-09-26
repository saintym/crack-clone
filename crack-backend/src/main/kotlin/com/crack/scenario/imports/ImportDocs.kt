package com.crack.scenario.imports

import com.crack.memory.docs.CharacterDoc
import com.crack.memory.docs.MarkdownSections
import com.crack.memory.docs.MemoryDocs
import com.crack.memory.docs.ProtagonistDoc
import java.time.LocalDate

/**
 * LLM이 쓴 마크다운을 **우리 파서가 읽을 수 있는 문서**로 다듬는다 (DESIGN.md §11.3).
 *
 * LLM 출력은 형식이 흔들리므로 믿지 않고 여기서 강제한다.
 * - 인물 문서: `- **이름**:` 보장, `## 기억` 섹션을 **빈 상태로** 둔다(T05 `CharacterDoc`)
 * - 주인공 문서: `- **이름**:` 보장, `## 변화 기록` 빈 섹션(T05 `ProtagonistDoc`)
 * - `keywords.md`: `## 제목` + `키워드:` 줄(T06 `KeywordBookParser`)
 * - `images.md`: `- 태그: URL | 설명`(T20 `ImageCatalogParser`)
 *
 * 출처 한 줄(`<!-- 출처: … -->`)은 문서 맨 아래에 남긴다. 단 인물·주인공 문서는 맨 아래가
 * 자동 기록 섹션(`## 기억`, `## 변화 기록`)이어야 하므로 그 **바로 위**에 둔다.
 * 기록이 돌 때 섹션 본문이 통째로 교체되어 출처가 지워지는 것을 막기 위해서다.
 */
object ImportDocs {

    /** 파일명·이미지 태그에 쓸 수 없는 문자. 태그 규칙(§8.5)과 경로 안전성을 함께 만족시킨다. */
    private val UNSAFE_IN_NAME = Regex("""[/\\:{}|*?"'<>\u0000-\u001F\s]+""")

    fun sourceLine(url: String, date: LocalDate = LocalDate.now()): String =
        "<!-- 출처: $url (자동 생성 $date) -->"

    /**
     * 인물 이름을 파일명 겸 이미지 태그 접두사로 쓸 수 있게 다듬는다.
     *
     * 경로 조각 하나여야 하고(`/`, `\`, `..` 금지), 이미지 태그 규칙상 공백과 `: { } |`도 쓸 수 없으므로
     * `_`로 바꾼다. 다듬을 것이 없으면 이름 그대로다. 쓸 수 없는 이름이면 null.
     */
    fun safeName(raw: String): String? {
        val collapsed = UNSAFE_IN_NAME.replace(raw.trim(), "_").trim('_', '.')
        if (collapsed.isEmpty() || collapsed == "." || collapsed == "..") return null
        return collapsed.take(80)
    }

    /** 이미 쓴 이름이면 `-2`, `-3`을 붙여 유일하게 만든다. [used]에 결과를 넣는다. */
    fun uniqueName(used: MutableSet<String>, name: String): String {
        if (used.add(name)) return name
        var n = 2
        while (!used.add("$name-$n")) n++
        return "$name-$n"
    }

    // --- 문서 만들기 ---

    /**
     * `prologue.md`. 첫 줄의 `[인물: …]` 태그(§5.3, D31)를 **인물 파일명으로 맞춘다**(T29).
     *
     * LLM은 인물을 화면에 보이는 이름으로 쓰지만 이미지 태그는 파일명(`{파일명}_기본`)을 쓰므로,
     * 이름을 다듬은 인물([safeName])이면 태그가 어긋나 첫 메시지에 이미지가 붙지 않는다.
     * 알 수 없는 이름이면 태그를 지운다(태그만 남아도 이미지는 붙지 않는다).
     * 변형은 지운다. 가져오기가 만든 카탈로그에는 `_기본`만 있다.
     *
     * @param characterFileNames 화면 이름 → 인물 파일명
     */
    fun prologue(body: String, source: String, characterFileNames: Map<String, String>): String {
        val text = clean(body)
        val newline = text.indexOf('\n')
        val firstLine = if (newline < 0) text else text.substring(0, newline)
        val rest = if (newline < 0) "" else text.substring(newline + 1)
        val fixed = fixSpeakerTag(firstLine, characterFileNames)
        val merged = if (fixed.isBlank() && firstLine.isNotBlank()) rest else fixed + "\n" + rest
        return plain(merged, source)
    }

    /** 첫 줄의 인물 태그를 파일명으로 바꾼다. 찾지 못하면 태그를 지운다. 태그가 없으면 줄 그대로. */
    private fun fixSpeakerTag(line: String, characterFileNames: Map<String, String>): String {
        val match = SPEAKER_TAG.find(line) ?: return line
        val name = match.groupValues[1].substringBefore('/').trim()
        val fileName = characterFileNames[name]
            ?: characterFileNames.entries.firstOrNull { it.key.replace(" ", "") == name.replace(" ", "") }?.value
            ?: characterFileNames.values.firstOrNull { it == name }
        val replacement = if (fileName == null) "" else "[인물: $fileName]"
        return line.replaceRange(match.range, replacement).trim()
    }

    /** `world.md`, `scenario.md`처럼 자동 기록 섹션이 없는 문서. */
    fun plain(body: String, source: String, fallbackTitle: String? = null): String {
        val text = clean(body).ifBlank { fallbackTitle?.let { "# $it\n" }.orEmpty() }
        return text.trimEnd() + "\n\n" + source + "\n"
    }

    /**
     * 인물 문서. `- **이름**: {name}`을 보장하고, 이름이 [displayName]과 다르면
     * 원래 이름을 별칭에 넣는다(파일명을 다듬은 경우 대화에서 여전히 찾히게).
     */
    fun character(fileName: String, displayName: String, body: String, source: String): String {
        var text = clean(body)
        if (text.isBlank()) text = "# 캐릭터: $displayName\n"
        text = ensureTitle(text, "캐릭터: $displayName")
        text = ensureField(text, "이름", displayName)
        if (fileName != displayName) text = ensureAlias(text, displayName)
        text = dropSection(text, CharacterDoc.MEMORY)
        text = dropSection(text, ProtagonistDoc.CHANGES) // 인물 문서에는 변화 기록이 없다
        return text.trimEnd() + "\n\n" + source + "\n\n## " + CharacterDoc.MEMORY + "\n"
    }

    /** 주인공 문서. `## 변화 기록`을 빈 섹션으로 둔다. */
    fun protagonist(displayName: String?, body: String, source: String): String {
        var text = clean(body).ifBlank { "# 주인공 (사용자)\n" }
        text = ensureTitle(text, "주인공 (사용자)")
        if (!displayName.isNullOrBlank()) text = ensureField(text, "이름", displayName)
        text = dropSection(text, ProtagonistDoc.CHANGES)
        text = dropSection(text, CharacterDoc.MEMORY)
        return text.trimEnd() + "\n\n" + source + "\n\n## " + ProtagonistDoc.CHANGES + "\n"
    }

    /**
     * `keywords.md`. `## 제목` 항목마다 `키워드:` 줄을 보장한다(없으면 제목을 키로 넣는다).
     * 내용이 없는 항목은 버린다(T06 파서가 건너뛰므로 남겨도 쓸모가 없다).
     */
    fun keywords(body: String, source: String): String {
        val text = clean(body)
        val sections = MarkdownSections.sections(text, level = 2)
        if (sections.isEmpty()) return plain("# 키워드북", source)
        val out = StringBuilder("# 키워드북\n")
        for (section in sections) {
            val title = section.title.trim()
            val lines = section.body(text).lines().dropWhile { it.isBlank() }
            val hasKeywordLine = lines.firstOrNull()?.let { KEYWORD_LINE.containsMatchIn(it) } == true
            val content = (if (hasKeywordLine) lines.drop(1) else lines).joinToString("\n").trim()
            if (content.isEmpty()) continue
            out.append("\n## ").append(title).append('\n')
            out.append(if (hasKeywordLine) lines.first().trim() else "키워드: $title").append('\n')
            out.append(content).append('\n')
        }
        return out.toString().trimEnd() + "\n\n" + source + "\n"
    }

    /**
     * `images.md`. 인물 이미지는 `{이름}_기본`으로 등록하고(T27 규칙),
     * 인물과 맞지 않는 이미지는 **줄마다 따로** 주석으로 남긴다(파서는 주석을 읽지 않는다).
     *
     * 태그 이름을 미리 넣지 않는 이유(BUG-024): 여러 줄에 같은 태그 이름을 써 두면 사용자가 주석을 풀 때
     * 중복 태그가 되어 첫 줄만 인식된다. 태그 이름은 사용자가 정한다.
     */
    fun images(characterImages: List<Pair<String, String>>, sceneUrls: List<String>, source: String): String =
        buildString {
            append("# 이미지 카탈로그\n\n")
            append("<!-- 형식: - 태그: 주소 | 설명 · 인물 이미지는 {인물}_기본 (인물 파일명과 같게) -->\n\n")
            if (characterImages.isEmpty()) {
                append("<!-- 인물 이미지를 찾지 못했습니다. `- 이름_기본: 주소 | 설명` 형식으로 직접 등록하세요. -->\n")
            }
            characterImages.forEach { (name, url) ->
                append("- ").append(name).append("_기본: ").append(url)
                    .append(" | ").append(name).append("의 기본 이미지\n")
            }
            if (sceneUrls.isNotEmpty()) {
                append("\n<!-- 인물과 맞지 않는 이미지 ")
                append(sceneUrls.size)
                append("개입니다. 장면·배경으로 쓸 것만 아래 주석을 풀어 `- 태그: 주소 | 설명` 형식으로 고치세요. -->\n")
                sceneUrls.forEachIndexed { index, url ->
                    // 주석을 조기에 끝내는 문자열은 바꿔 둔다(주석 밖으로 새면 엉뚱한 줄이 남는다)
                    append("<!-- 미분류 이미지 ").append(index + 1).append(": ")
                        .append(url.replace("-->", "--%3E"))
                        .append(" — 쓰려면 주석을 풀고 태그 이름을 정하세요 -->\n")
                }
            }
            append('\n').append(source).append('\n')
        }

    // --- 내부 ---

    /** 코드 펜스를 벗기고 줄바꿈을 LF로 맞춘다. */
    fun clean(body: String): String = unfence(body.replace("\r\n", "\n")).trim()

    private fun unfence(body: String): String {
        val trimmed = body.trim()
        if (!trimmed.startsWith("```") && !trimmed.startsWith("~~~")) return trimmed
        val fence = trimmed.substring(0, 3)
        val firstNewline = trimmed.indexOf('\n')
        if (firstNewline < 0 || !trimmed.endsWith(fence)) return trimmed
        return trimmed.substring(firstNewline + 1, trimmed.length - 3).trim()
    }

    private fun ensureTitle(text: String, title: String): String =
        if (text.trimStart().startsWith("#")) text else "# $title\n\n$text"

    /** `- **{field}**: {value}` 줄을 보장한다. 이미 값이 있으면 그대로 둔다. */
    private fun ensureField(text: String, field: String, value: String): String {
        val current = MemoryDocs.parseField(text, field)
        if (!current.isNullOrBlank() && !(current.startsWith("(") && current.endsWith(")"))) return text
        val line = "- **$field**: $value"
        if (current != null) { // 줄은 있는데 값이 비었다 → 그 줄을 채운다
            return text.lineSequence().joinToString("\n") { raw ->
                if (fieldLine(field).containsMatchIn(raw)) line else raw
            }
        }
        val basic = MarkdownSections.find(text, "기본 정보")
        if (basic != null) {
            return text.substring(0, basic.bodyStart) + line + "\n" + text.substring(basic.bodyStart)
        }
        // `## 기본 정보`가 없으면 만들어 문서 맨 앞(제목 다음)에 넣는다
        val firstNewline = text.indexOf('\n')
        val head = if (firstNewline < 0) text else text.substring(0, firstNewline + 1)
        val rest = if (firstNewline < 0) "" else text.substring(firstNewline + 1)
        return head + "\n## 기본 정보\n" + line + "\n" + rest
    }

    /** `- **별칭**:` 줄에 [alias]를 더한다(없으면 줄을 만든다). */
    private fun ensureAlias(text: String, alias: String): String {
        val existing = MemoryDocs.parseAliases(text)
        if (existing.contains(alias)) return text
        val merged = (listOf(alias) + existing).joinToString(", ")
        val line = "- **별칭**: $merged"
        if (MemoryDocs.parseField(text, "별칭") != null) {
            return text.lineSequence().joinToString("\n") { raw ->
                if (fieldLine("별칭").containsMatchIn(raw)) line else raw
            }
        }
        val nameLine = fieldLine("이름")
        val lines = text.lines().toMutableList()
        val index = lines.indexOfFirst { nameLine.containsMatchIn(it) }
        if (index >= 0) lines.add(index + 1, line) else lines.add(0, line)
        return lines.joinToString("\n")
    }

    private fun dropSection(text: String, title: String): String {
        var result = text
        while (true) {
            val section = MarkdownSections.find(result, title) ?: return result
            result = MarkdownSections.removeSection(result, section)
        }
    }

    private val KEYWORD_LINE = Regex("""^\s*키워드\s*[:：]""")

    /** 첫 줄의 인물 태그. [com.crack.chat.flow.EmotionTagFilter.TAG]와 같은 형식이다. */
    private val SPEAKER_TAG = Regex("""\[\s*인물\s*:([^\[\]]{0,200})]""")

    private fun fieldLine(field: String) =
        Regex("""^\s{0,3}[-*+]\s*\*\*${Regex.escape(field)}\*\*\s*[:：]""")
}
