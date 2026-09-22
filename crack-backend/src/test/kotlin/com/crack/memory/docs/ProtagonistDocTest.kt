package com.crack.memory.docs

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ProtagonistDocTest {

    private val text = """
        |# 주인공 (사용자)
        |## 기본 정보
        |- **이름**: 한서진
        |- **별칭**: 서진, 한 공자
        |
        |## 변화 기록
        |### 관계
        |- 설월: 목숨을 구해 줌 (t21)
        |### 스탯·기술
        |- 경공 입문 (t12)
        |### 소지품
        |- 옥패를 설월에게 줌 (t21)
        |### 신체
        |- 왼팔에 칼자국 (t19)
        |""".trimMargin()

    @Test
    fun `변화 기록 하위 섹션을 구조체로 읽는다`() {
        val doc = ProtagonistDoc(text)
        assertEquals("한서진", doc.displayName())
        assertEquals(listOf("서진", "한 공자"), doc.parseAliases())

        val changes = doc.changes()
        assertEquals("설월", changes.relations.single().target)
        assertEquals(listOf("경공 입문 (t12)"), changes.statsAndSkills.map { it.text })
        assertEquals(listOf("옥패를 설월에게 줌 (t21)"), changes.possessions.map { it.text })
        assertEquals(listOf(19), changes.body.single().turns)
    }

    @Test
    fun `변화 기록만 교체하고 없으면 추가한다`() {
        val doc = ProtagonistDoc(text)
        val updated = doc.withChanges(doc.changes().copy(body = emptyList()))
        assertTrue(updated.text.startsWith(text.substring(0, text.indexOf("## 변화 기록"))))
        assertTrue(updated.changes().body.isEmpty())
        assertEquals(doc.changes().relations, updated.changes().relations)

        val bare = ProtagonistDoc("# 주인공\n- **이름**:\n")
        assertNull(bare.displayName())
        assertNull(bare.changesSection)
        val added = bare.withChangesSection("### 신체\n- 흉터 (t1)")
        assertEquals("# 주인공\n- **이름**:\n\n## 변화 기록\n### 신체\n- 흉터 (t1)\n", added.text)
    }
}
