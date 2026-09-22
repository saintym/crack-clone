package com.crack.memory.docs

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.runner.ApplicationContextRunner

class MemoryBudgetsTest {

    private val runner = ApplicationContextRunner().withUserConfiguration(MemoryDocsConfig::class.java)

    @Test
    fun `설정이 없으면 DESIGN 기본값을 쓴다`() {
        runner.run { ctx ->
            assertEquals(MemoryBudgets(3000, 4000, 12000), ctx.getBean(MemoryBudgets::class.java))
        }
    }

    @Test
    fun `crack memory budget 설정을 바인딩한다`() {
        runner.withPropertyValues(
            "crack.memory.budget.character=10",
            "crack.memory.budget.protagonist=20",
            "crack.memory.budget.chronicle=30",
        ).run { ctx ->
            assertEquals(MemoryBudgets(10, 20, 30), ctx.getBean(MemoryBudgets::class.java))
        }
    }

    @Test
    fun `예산을 넘을 때만 exceeds가 참이다`() {
        val budgets = MemoryBudgets(character = 5, protagonist = 5, chronicle = 30)
        assertFalse(budgets.exceeds(MemoryBudgets.Kind.CHARACTER, 5))
        assertTrue(budgets.exceeds(MemoryBudgets.Kind.CHARACTER, 6))

        assertFalse(budgets.exceeds(CharacterDoc("a", "## 기억\n\n12345\n\n")))
        assertTrue(budgets.exceeds(CharacterDoc("a", "## 기억\n123456\n")))
        assertFalse(budgets.exceeds(CharacterDoc("a", "# 기억 없음\n" + "x".repeat(100))))

        assertTrue(budgets.exceeds(ProtagonistDoc("## 변화 기록\n123456\n")))

        val chronicle = Chronicle("# 연대기\n## 장 요약\n${"요".repeat(100)}\n## 회차 1 (턴 1–10)\n- a\n")
        assertFalse(budgets.exceeds(chronicle)) // 장 요약은 세지 않는다
        assertTrue(budgets.exceeds(chronicle.append(ChronicleEntry(2, 11, 20, "- b"))))
    }
}
