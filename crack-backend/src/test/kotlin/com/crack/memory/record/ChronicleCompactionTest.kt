package com.crack.memory.record

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * 연대기 압축 트리거와 접는 개수 규칙 (D45, T24 완료 조건).
 * Spring 없이 [MemoryRecordPipeline.overEntryLimit]·[MemoryRecordPipeline.foldCount]만 본다.
 */
class ChronicleCompactionTest {

    private fun over(entryCount: Int, maxEntries: Int) = MemoryRecordPipeline.overEntryLimit(entryCount, maxEntries)
    private fun fold(entryCount: Int, maxEntries: Int) = MemoryRecordPipeline.foldCount(entryCount, maxEntries)

    @Test
    fun `회차 수가 상한을 넘어야 트리거된다`() {
        assertThat(over(4, 4)).isFalse()
        assertThat(over(5, 4)).isTrue()
        assertThat(over(12, 4)).isTrue()
    }

    @Test
    fun `상한이 0 이하면 회차 수 트리거를 쓰지 않는다`() {
        assertThat(over(100, 0)).isFalse()
        assertThat(over(100, -1)).isFalse()
    }

    @Test
    fun `회차 수로 걸리면 목표 개수까지 한 번에 접는다`() {
        assertThat(fold(12, 4)).isEqualTo(8) // 12 - 4, 남는 회차 4개
        assertThat(fold(9, 4)).isEqualTo(5)
        assertThat(fold(7, 4)).isEqualTo(3)
    }

    @Test
    fun `글자 예산으로만 걸리면 예전처럼 절반을 접는다`() {
        assertThat(fold(10, 0)).isEqualTo(5) // 회차 수 트리거 꺼짐
        assertThat(fold(2, 0)).isEqualTo(1)
        assertThat(fold(3, 0)).isEqualTo(1)
        assertThat(fold(4, 8)).isEqualTo(2) // 상한 밑이라 예산으로 걸린 것
    }

    @Test
    fun `최근 1개는 반드시 남기고 회차가 1개면 접지 않는다`() {
        assertThat(fold(1, 4)).isEqualTo(0)
        assertThat(fold(0, 4)).isEqualTo(0)
        assertThat(fold(20, 1)).isEqualTo(19)
        assertThat(fold(2, 1)).isEqualTo(1)
    }
}
