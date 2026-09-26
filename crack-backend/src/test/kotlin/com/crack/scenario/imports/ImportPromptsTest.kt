package com.crack.scenario.imports

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * 프롬프트 조립.
 *
 * 여러 줄 값을 `""" … $answers … """.trimIndent()` 안에 끼우면 공통 들여쓰기가 0이 되어
 * 템플릿 전체가 8칸 들여쓰인 채 나간다. 답변 구획을 trimIndent **뒤에** 붙여 막았고, 이 테스트로 고정한다.
 */
class ImportPromptsTest {

    private val questions = listOf(
        ImportQuestion("q1", "주인공의 이름은?"),
        ImportQuestion("q2", "시작 시점은?"),
    )

    private fun assertNotIndented(prompt: String) {
        assertThat(prompt.lines().first()).doesNotStartWith(" ")
        val indented = prompt.lines().count { it.startsWith("        ") }
        assertThat(indented).isZero()
    }

    @Test
    fun `여러 줄 답변을 넣어도 들여쓰기가 남지 않는다`() {
        val answers = ImportPrompts.formatAnswers(questions, mapOf("q1" to "무명", "q2" to "봄"))
        assertThat(answers).contains("주인공의 이름은?").contains("무명")

        listOf(
            ImportPrompts.worldStep(answers),
            ImportPrompts.charactersStep(listOf("휘령", "혜연"), answers),
            ImportPrompts.protagonistStep(answers, listOf("휘령")),
        ).forEach { prompt ->
            assertNotIndented(prompt)
            assertThat(prompt).contains("무명")
        }
    }

    @Test
    fun `단계 지시는 단계 번호로 시작한다`() {
        val answers = ImportPrompts.formatAnswers(questions, emptyMap())

        assertThat(ImportPrompts.worldStep(answers)).startsWith("1단계")
        assertThat(ImportPrompts.charactersStep(listOf("휘령"), answers)).startsWith("2단계")
        assertThat(ImportPrompts.protagonistStep(answers, listOf("휘령"))).startsWith("3단계")
        assertThat(ImportPrompts.analyze()).doesNotStartWith(" ")
    }

    @Test
    fun `답이 없으면 안내 문구를 넣는다`() {
        val answers = ImportPrompts.formatAnswers(questions, mapOf("q1" to "  "))

        assertThat(answers).contains("답하지 않았다")
    }

    @Test
    fun `질문에 없는 답도 버리지 않는다`() {
        val answers = ImportPrompts.formatAnswers(questions, mapOf("자유" to "야명 소속"))

        assertThat(answers).contains("자유").contains("야명 소속")
    }

    @Test
    fun `이름에 줄바꿈이 섞여도 한 줄로 만든다`() {
        val prompt = ImportPrompts.charactersStep(listOf("휘\n령", "혜연"), "(없음)")

        assertNotIndented(prompt)
        assertThat(prompt).contains("대상 (2명): 휘 령, 혜연")
    }

    @Test
    fun `시스템 프롬프트에 추출 컨텍스트가 들어간다`() {
        val page = HtmlExtractor(ImportProperties())
            .extract("https://e.com/a.html", "<title>제목</title><body>본문</body><script>const A=[1];</script>")

        val system = ImportPrompts.system(page)

        assertThat(system).startsWith("너는 웹페이지의 설정 자료를")
        assertThat(system).contains("=== 페이지 자료 시작 ===")
        assertThat(system).contains("### A")
        assertThat(system).contains("본문")
        assertThat(system).endsWith("=== 페이지 자료 끝 ===\n")
    }
}
