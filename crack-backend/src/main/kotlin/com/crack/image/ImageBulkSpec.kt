package com.crack.image

/**
 * 이미지 카탈로그 일괄 등록 명세 파서 (DESIGN.md §8.6, T41).
 *
 * 이미지가 `{캐릭터 코드}`와 `{행동 코드}`의 조합으로 규칙적인 주소를 가질 때, 표 두 개와 주소 틀만 주면
 * 조합을 모두 펼쳐 [ImageEntry] 목록을 만든다. 손으로 수십 줄을 적지 않게 하는 것이 목적이다.
 *
 * ```
 * #형식: ![](https://example.com/{c}_{a}.png)
 *
 * # 캐릭터 코드
 * *F01: 완안연
 * *F02: 신소향
 *
 * # 행동
 * *1: 기본/대화
 * *2: 미소
 * *3~4: 놀람
 * ```
 *
 * 규칙
 * - **주소 틀**: `형식`이 들어간 제목 줄의 `:` 뒤. 마크다운 이미지(`![](...)`)면 주소만 꺼낸다.
 *   `{c}`(캐릭터 코드)와 `{a}`(행동 코드)가 둘 다 있어야 한다.
 * - **캐릭터 표**: 제목에 `캐릭터`가 들어간 구역. 그 외 구역은 모두 **행동 표**로 합친다
 *   (`# 행동`, `#H` 처럼 구역이 여러 개여도 된다).
 * - **항목 줄**: `*코드: 이름` (`-`, `+`도 된다).
 * - **코드 범위**: `3~4`, `3-4`, `3～4`는 숫자 양쪽일 때만 펼친다. 자리수는 왼쪽에 맞춘다(`01~03` → `01,02,03`).
 * - **변형 이름**: 라벨의 첫 `/` 앞을 쓴다(`기본/대화` → `기본`). `/`는 `[인물: 이름/변형]`의 구분자라 태그에 쓸 수 없다.
 *   공백은 `_`로 바꾸고 태그에 못 쓰는 글자는 버린다. 같은 이름이 겹치면 뒤에 번호를 붙인다.
 * - **설명**: 라벨 원문을 그대로 쓴다. AI가 변형을 고르는 기준이 된다.
 */
object ImageBulkSpecParser {

    /** 코드 하나와 그것이 가리키는 이름 */
    data class Code(val code: String, val label: String)

    data class Result(
        val urlTemplate: String,
        val characters: List<Code>,
        val actions: List<Code>,
        val entries: List<ImageEntry>,
        val warnings: List<String>,
    )

    const val CHARACTER_PLACEHOLDER = "{c}"
    const val ACTION_PLACEHOLDER = "{a}"

    /** 한 번에 만들 수 있는 항목 수 상한. 실수로 거대한 표를 넣었을 때를 막는다 */
    const val MAX_ENTRIES = 2_000

    private val HEADING = Regex("""^\s*#+\s*(.+?)\s*$""")
    private val ITEM = Regex("""^\s*[-*+]\s*([^:：]+)[:：]\s*(.*)$""")
    private val MARKDOWN_IMAGE = Regex("""!\[[^]]*]\(\s*([^)\s]+)\s*\)""")
    private val URL_IN_TEXT = Regex("""https?://\S+""")
    private val RANGE = Regex("""^(\d+)\s*[~\-～]\s*(\d+)$""")
    private val TAG_FORBIDDEN = Regex("""[\s:{}|]+""")

    /**
     * 칸을 따로 받는 입구 (T42). 화면이 세 칸으로 나뉘어 있을 때 쓴다.
     *
     * 각 표는 한 줄에 `코드: 이름` 하나다. 앞에 `*`, `-`, `+`가 붙어 있어도 되고, 빈 줄과 `#` 줄은 무시한다.
     * 제목 줄을 찾지 않으므로 **어느 칸이 캐릭터인지 헷갈릴 일이 없다.**
     */
    fun parseFields(urlTemplate: String, charactersText: String, actionsText: String): Result {
        val warnings = mutableListOf<String>()
        val template = extractUrl(urlTemplate).ifEmpty { urlTemplate.trim() }
        val characters = parseCodeLines(charactersText, "캐릭터", warnings)
        val actions = parseCodeLines(actionsText, "행동", warnings)
        return assemble(template, characters, actions, warnings)
    }

    /** `코드: 이름` 줄 목록. 글머리표와 빈 줄, `#` 줄은 건너뛴다. */
    private fun parseCodeLines(text: String, what: String, warnings: MutableList<String>): List<Code> {
        val codes = mutableListOf<Code>()
        for (raw in text.lines()) {
            val line = raw.trim().removePrefix("*").removePrefix("-").removePrefix("+").trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val separator = line.indexOfFirst { it == ':' || it == '：' }
            if (separator <= 0) {
                warnings += "$what 줄을 읽지 못해 건너뜁니다(`코드: 이름` 형식이어야 합니다): ${raw.trim()}"
                continue
            }
            val codePart = line.substring(0, separator).trim()
            val label = line.substring(separator + 1).trim()
            if (label.isEmpty()) {
                warnings += "$what 이름이 비어 있어 건너뜁니다: ${raw.trim()}"
                continue
            }
            val expanded = expandCodes(codePart)
            if (expanded.isEmpty()) {
                warnings += "$what 코드를 읽지 못해 건너뜁니다: ${raw.trim()}"
                continue
            }
            expanded.forEach { codes += Code(it, label) }
        }
        return codes
    }

    fun parse(spec: String): Result {
        val warnings = mutableListOf<String>()
        var template = ""
        val characters = mutableListOf<Code>()
        val actions = mutableListOf<Code>()
        var inCharacterSection = false

        for (raw in spec.lines()) {
            val heading = HEADING.find(raw)?.groupValues?.get(1)
            if (heading != null) {
                val title = heading.trim()
                if (title.contains("형식")) {
                    template = extractUrl(title.substringAfter(':', title.substringAfter('：', "")))
                    continue
                }
                inCharacterSection = title.contains("캐릭터")
                continue
            }
            val item = ITEM.find(raw) ?: continue
            val codePart = item.groupValues[1].trim()
            val label = item.groupValues[2].trim()
            if (label.isEmpty()) {
                warnings += "이름이 비어 있어 건너뜁니다: ${raw.trim()}"
                continue
            }
            val codes = expandCodes(codePart)
            if (codes.isEmpty()) {
                warnings += "코드를 읽지 못해 건너뜁니다: ${raw.trim()}"
                continue
            }
            val target = if (inCharacterSection) characters else actions
            codes.forEach { target += Code(it, label) }
        }

        // 주소 틀이 제목 줄에 없으면 본문 어디에서라도 찾아 본다
        if (template.isEmpty()) template = extractUrl(spec)

        return assemble(template, characters, actions, warnings)
    }

    /** 두 입구가 공유하는 검사와 조립. */
    private fun assemble(
        template: String,
        characters: List<Code>,
        actions: List<Code>,
        warnings: MutableList<String>,
    ): Result {
        if (template.isEmpty()) {
            warnings += "주소 틀이 비어 있습니다. `https://…/{c}_{a}.png` 형태가 필요합니다."
        } else {
            if (!template.contains(CHARACTER_PLACEHOLDER)) warnings += "주소 틀에 $CHARACTER_PLACEHOLDER 가 없습니다."
            if (!template.contains(ACTION_PLACEHOLDER)) warnings += "주소 틀에 $ACTION_PLACEHOLDER 가 없습니다."
        }
        if (characters.isEmpty()) warnings += "캐릭터 목록이 비어 있습니다."
        if (actions.isEmpty()) warnings += "행동·상황 목록이 비어 있습니다."

        val entries = if (template.isEmpty()) emptyList() else build(template, characters, actions, warnings)
        return Result(template, characters, actions, entries, warnings)
    }

    /** 캐릭터 × 행동을 모두 펼친다. 같은 태그는 뒤에 번호를 붙여 구별한다. */
    private fun build(
        template: String,
        characters: List<Code>,
        actions: List<Code>,
        warnings: MutableList<String>,
    ): List<ImageEntry> {
        val entries = mutableListOf<ImageEntry>()
        var truncated = false
        for (character in characters) {
            val used = HashMap<String, Int>()
            for (action in actions) {
                if (entries.size >= MAX_ENTRIES) { truncated = true; break }
                val variant = variantName(action.label)
                if (variant.isEmpty()) {
                    warnings += "변형 이름을 만들 수 없어 건너뜁니다: ${action.label}"
                    continue
                }
                val count = used.merge(variant, 1, Int::plus)!!
                val suffix = if (count == 1) "" else count.toString()
                val url = template
                    .replace(CHARACTER_PLACEHOLDER, character.code)
                    .replace(ACTION_PLACEHOLDER, action.code)
                entries += ImageEntry("${character.label}_$variant$suffix", url, action.label)
            }
            if (truncated) break
        }
        if (truncated) warnings += "항목이 $MAX_ENTRIES 개를 넘어 나머지를 버렸습니다."
        return entries
    }

    /** 라벨에서 태그에 쓸 변형 이름을 만든다. */
    fun variantName(label: String): String =
        label.substringBefore('/').trim().replace(TAG_FORBIDDEN, "_").trim('_')

    /** `3~4`는 3,4로 펼친다. 범위가 아니면 그 코드 하나. */
    fun expandCodes(codePart: String): List<String> {
        if (codePart.isEmpty()) return emptyList()
        val range = RANGE.find(codePart) ?: return listOf(codePart)
        val from = range.groupValues[1]
        val to = range.groupValues[2]
        val start = from.toIntOrNull() ?: return listOf(codePart)
        val end = to.toIntOrNull() ?: return listOf(codePart)
        if (end < start || end - start > 200) return listOf(codePart)
        val width = from.length
        return (start..end).map { it.toString().padStart(width, '0') }
    }

    /** 마크다운 이미지나 본문에서 주소를 꺼낸다. 없으면 빈 문자열. */
    private fun extractUrl(text: String): String {
        MARKDOWN_IMAGE.find(text)?.let { return it.groupValues[1].trim() }
        URL_IN_TEXT.find(text)?.let { return it.value.trim().trimEnd(')') }
        return ""
    }
}
