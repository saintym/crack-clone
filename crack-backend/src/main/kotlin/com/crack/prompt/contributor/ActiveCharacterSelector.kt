package com.crack.prompt.contributor

import com.crack.memory.docs.CharacterDoc
import com.crack.memory.docs.MemoryDocs
import com.crack.memory.docs.StoryState
import com.crack.prompt.config.PromptProperties
import com.crack.prompt.keyword.KeywordEntry
import com.crack.prompt.keyword.KeywordMatcher
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.nio.file.Path

/**
 * 활성 인물 선택 (DESIGN.md §6.2, D9).
 *
 * `state.json.companions` ∪ [KeywordMatcher]가 `recentText`에서 찾은 인물(파일명과 `별칭`).
 * - 순서: 동행 인물(`companions` 순서) → 키워드로 찾은 인물(파일명 순). 중복은 한 번만.
 * - 동행 이름은 파일명과 먼저 비교하고, 없으면 별칭이 정확히 같은 인물을 쓴다. 둘 다 없으면 버린다.
 * - 주인공(`protagonist.md`)은 대상이 아니다. [ProtagonistContributor]가 항상 넣는다.
 * - **`crack.prompt.max-active-characters`(기본 6)로 수를 자른다**(T44). 인물 문서는 프롬프트에서
 *   가장 비싼 부분이라(1명당 약 1,460자) 상한이 없으면 한 턴에 인물 이름이 여러 개 나올 때 폭주한다.
 *   순서가 곧 우선순위다 — **동행 인물이 먼저 남고 키워드로 걸린 인물부터 잘린다.**
 */
@Component
class ActiveCharacterSelector(
    private val keywordMatcher: KeywordMatcher,
    private val properties: PromptProperties = PromptProperties(),
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun select(ctx: PromptContext): List<CharacterDoc> = select(ctx.storyDir, ctx.recentText)

    fun select(storyDir: Path, recentText: String): List<CharacterDoc> {
        val docs = MemoryDocs.readCharacters(storyDir)
        if (docs.isEmpty()) return emptyList()
        val byName = docs.associateBy { it.name }
        val aliases = docs.associate { it.name to it.parseAliases() }

        val selected = LinkedHashMap<String, CharacterDoc>()
        for (companion in readCompanions(storyDir)) {
            val name = companion.trim()
            val doc = byName[name] ?: docs.firstOrNull { d -> aliases.getValue(d.name).any { it == name } }
            if (doc != null) selected.putIfAbsent(doc.name, doc)
        }

        val entries = docs.map { KeywordEntry(it.name, listOf(it.name) + aliases.getValue(it.name)) }
        keywordMatcher.match(entries, recentText).forEach { name -> selected.putIfAbsent(name, byName.getValue(name)) }

        val all = selected.values.toList()
        val max = properties.maxActiveCharacters
        if (max <= 0 || all.size <= max) return all
        log.info(
            "활성 인물이 {}명이라 앞에서 {}명만 씁니다(동행 인물 우선). 버린 인물: {}",
            all.size, max, all.drop(max).joinToString(", ") { it.name },
        )
        return all.take(max)
    }

    private fun readCompanions(storyDir: Path): List<String> =
        try {
            MemoryDocs.readState(storyDir).companions
        } catch (e: Exception) {
            log.warn("state.json을 읽지 못해 동행 인물 없이 진행합니다: {} ({})", MemoryDocs.statePath(storyDir), e.message)
            StoryState.EMPTY.companions
        }
}
