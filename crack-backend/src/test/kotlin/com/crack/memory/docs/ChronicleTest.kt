package com.crack.memory.docs

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class ChronicleTest {

    private val text = """
        |# 연대기
        |## 회차 1 (턴 1–10)
        |- 주인공이 객잔에 도착
        |
        |## 회차 2 (턴 11–20)
        |- 설월과 만남
        |
        |## 회차 3 (턴 21–30)
        |- 흑풍채 습격
        |""".trimMargin()

    @Test
    fun `장 요약과 회차 목록을 파싱한다`() {
        val chronicle = Chronicle(text)
        assertNull(chronicle.summary)
        val entries = chronicle.entries()
        assertEquals(listOf(1, 2, 3), entries.map { it.number })
        assertEquals(ChronicleEntry(2, 11, 20, "- 설월과 만남"), entries[1])
        assertEquals(4, chronicle.nextNumber())
        assertEquals(30, chronicle.lastTurn())

        val withSummary = Chronicle("# 연대기\n## 장 요약\n요약문\n## 회차 4 (턴 31-40)\n- x\n")
        assertEquals(ChronicleParts("요약문", listOf(ChronicleEntry(4, 31, 40, "- x"))), withSummary.split())
    }

    @Test
    fun `회차를 끝에 추가한다`() {
        val appended = Chronicle(text).append(ChronicleEntry(4, 31, 40, "- 설월 회복\n- 옥패 전달"))
        assertEquals(text + "\n## 회차 4 (턴 31–40)\n- 설월 회복\n- 옥패 전달\n", appended.text)
        assertEquals(4, appended.entries().size)

        val fresh = Chronicle.empty().append(ChronicleEntry(1, 1, 10, "- 시작"))
        assertEquals("# 연대기\n\n## 회차 1 (턴 1–10)\n- 시작\n", fresh.text)
        assertEquals(fresh.text, Chronicle("").append(ChronicleEntry(1, 1, 10, "- 시작")).text)
    }

    @Test
    fun `회차 원문 길이는 장 요약을 빼고 센다`() {
        val chronicle = Chronicle(text)
        val expected = listOf(
            "## 회차 1 (턴 1–10)\n- 주인공이 객잔에 도착",
            "## 회차 2 (턴 11–20)\n- 설월과 만남",
            "## 회차 3 (턴 21–30)\n- 흑풍채 습격",
        ).sumOf { it.length }
        assertEquals(expected, chronicle.rawLength())
        assertEquals(expected, chronicle.replaceOldestWithSummary(0, "긴 요약".repeat(100)).rawLength())
    }

    @Test
    fun `오래된 회차를 요약으로 치환한다`() {
        val chronicle = Chronicle(text)
        assertEquals(listOf(1, 2), chronicle.oldestEntries(2).map { it.number })
        assertEquals(3, chronicle.oldestEntries(10).size)

        val compacted = chronicle.replaceOldestWithSummary(2, "객잔에 온 주인공이 설월을 만났다.")
        assertEquals(
            "# 연대기\n## 장 요약\n객잔에 온 주인공이 설월을 만났다.\n\n## 회차 3 (턴 21–30)\n- 흑풍채 습격\n",
            compacted.text,
        )
        assertEquals("객잔에 온 주인공이 설월을 만났다.", compacted.summary)
        assertEquals(listOf(3), compacted.entries().map { it.number })

        // 다시 압축하면 기존 요약을 새 요약으로 바꾼다
        val again = compacted.append(ChronicleEntry(4, 31, 40, "- 회복")).compactOldest(1, "새 요약")
        assertEquals("# 연대기\n## 장 요약\n새 요약\n\n## 회차 4 (턴 31–40)\n- 회복\n", again.text)
        assertEquals(5, again.nextNumber())
    }

    @Test
    fun `빈 요약이면 장 요약 섹션을 없앤다`() {
        val src = "# 연대기\n## 장 요약\n옛 요약\n\n## 회차 2 (턴 11–20)\n- x\n"
        assertEquals("# 연대기\n## 회차 2 (턴 11–20)\n- x\n", Chronicle(src).replaceOldestWithSummary(0, " ").text)
    }

    @Test
    fun `파일 왕복과 없는 파일`(@TempDir dir: Path) {
        assertEquals(Chronicle.empty(), MemoryDocs.readChronicle(dir))
        Chronicle(text).write(MemoryDocs.chroniclePath(dir))
        assertEquals(Chronicle(text), MemoryDocs.readChronicle(dir))
    }
}
