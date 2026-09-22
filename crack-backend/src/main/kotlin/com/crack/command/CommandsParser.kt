package com.crack.command

/** `commands.md`의 사용자 정의 명령 하나 (DESIGN.md §8.2). [name]에는 `/`를 붙이지 않는다. */
data class CustomCommand(
    val name: String,
    val description: String,
    val prompt: String,
)

/**
 * `commands.md` 파서 (DESIGN.md §8.2).
 *
 * ```markdown
 * ## /일기
 * 설명: 주인공의 하루를 일기 형식으로 정리
 * 프롬프트: 지금까지의 일을 주인공 시점의 일기로 써라. 이야기는 진행하지 마라.
 * ```
 * - `## /이름`(또는 `## 이름`) 제목 하나가 명령 하나다. 이름은 제목의 첫 단어에서 앞의 `/`를 뗀 것이다.
 * - `설명:` 줄이 설명이다. `프롬프트:` 줄과 그 뒤의 줄(다음 제목 전까지)이 프롬프트다.
 *   `프롬프트:`가 없으면 `설명:`을 뺀 본문 전체가 프롬프트다.
 * - 프롬프트가 빈 명령, [reserved](시스템 명령)와 같은 이름, 앞에서 이미 나온 이름은 버린다(대소문자 무시).
 */
object CommandsParser {
    private val HEADING = Regex("""^##(?!#)\s*(.*)$""")
    private val DESCRIPTION = Regex("""^설명\s*[:：]\s*(.*)$""")
    private val PROMPT = Regex("""^프롬프트\s*[:：]\s*(.*)$""")

    fun parse(markdown: String, reserved: Collection<String> = emptyList()): List<CustomCommand> {
        val sections = mutableListOf<Pair<String, MutableList<String>>>()
        for (line in markdown.replace("\r\n", "\n").split("\n")) {
            val heading = HEADING.matchEntire(line.trimEnd())
            if (heading != null) {
                sections += heading.groupValues[1].trim() to mutableListOf()
            } else {
                sections.lastOrNull()?.second?.add(line)
            }
        }
        val seen = reserved.map { it.lowercase() }.toMutableSet()
        return sections.mapNotNull { (title, body) -> toCommand(title, body) }
            .filter { seen.add(it.name.lowercase()) }
    }

    private fun toCommand(title: String, body: List<String>): CustomCommand? {
        val name = title.split(Regex("\\s+")).firstOrNull().orEmpty().removePrefix("/").trim()
        if (name.isEmpty()) return null
        var description = ""
        val promptLines = mutableListOf<String>()
        val otherLines = mutableListOf<String>()
        var inPrompt = false
        for (line in body) {
            val trimmed = line.trim()
            val prompt = PROMPT.matchEntire(trimmed)
            val desc = DESCRIPTION.matchEntire(trimmed)
            when {
                !inPrompt && prompt != null -> {
                    inPrompt = true
                    promptLines += prompt.groupValues[1]
                }
                inPrompt -> promptLines += line
                desc != null && description.isEmpty() -> description = desc.groupValues[1].trim()
                else -> otherLines += line
            }
        }
        val prompt = (if (inPrompt) promptLines else otherLines).joinToString("\n").trim()
        if (prompt.isEmpty()) return null
        return CustomCommand(name, description, prompt)
    }
}
