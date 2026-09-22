package com.crack.prompt.contributor

import com.crack.memory.docs.ChronicleEntry
import com.crack.memory.docs.MemoryBudgets
import com.crack.memory.docs.MemoryDocs
import org.springframework.stereotype.Component

/**
 * SCENARIO(100): 연대기 (DESIGN.md §6 기본 기여자, §7.1).
 *
 * `## 장 요약` + 최근 회차 원문. 회차는 최신 것부터 거꾸로 `crack.memory.budget.chronicle` 안에서 담고,
 * 가장 최근 회차 하나는 예산을 넘어도 넣는다(예산 초과는 기록 파이프라인이 압축한다). 쓰는 순서는 파일 순서(오래된 것부터)다.
 */
@Component
class ChronicleContributor(private val budgets: MemoryBudgets) : PromptContributor {
    override val slot = PromptSlot.SCENARIO
    override val order = 100

    override fun contribute(ctx: PromptContext): String? {
        val chronicle = MemoryDocs.readChronicle(ctx.storyDir)
        val summary = chronicle.summary
        val entries = recentWithinBudget(chronicle.entries(), budgets.chronicle)
        if (summary == null && entries.isEmpty()) return null

        val body = buildString {
            if (summary != null) append("## 장 요약\n").append(summary).append("\n\n")
            entries.forEach { append(it.toMarkdown().trimEnd()).append("\n\n") }
        }
        return StoryDocs.titled("연대기 (지금까지의 이야기)", body)
    }

    companion object {
        /** 최신 회차부터 [budget] 글자 안에서 고른다. 가장 최근 회차는 항상 넣는다. 결과는 오래된 것부터. */
        fun recentWithinBudget(entries: List<ChronicleEntry>, budget: Int): List<ChronicleEntry> {
            val picked = ArrayDeque<ChronicleEntry>()
            var used = 0
            for (entry in entries.asReversed()) {
                val size = entry.toMarkdown().trim().length
                if (picked.isNotEmpty() && used + size > budget) break
                picked.addFirst(entry)
                used += size
            }
            return picked.toList()
        }
    }
}
