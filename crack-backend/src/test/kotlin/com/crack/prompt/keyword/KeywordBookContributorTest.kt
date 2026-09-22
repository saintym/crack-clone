package com.crack.prompt.keyword

import com.crack.prompt.config.PromptProperties
import com.crack.prompt.contributor.PromptContext
import com.crack.prompt.contributor.PromptSlot
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime

/** 키워드북 주입 (DESIGN.md §8.3): 발동, 우선순위, 최대 개수, 미언급 제외, 파일이 없을 때. */
class KeywordBookContributorTest {

    @TempDir lateinit var dir: Path

    private val book = """
        |# 키워드북
        |
        |## 천마신교
        |키워드: 천마신교, 마교, 신교
        |중원 서쪽의 마도 문파. 교주는 천마.
        |
        |## 흑풍채
        |키워드: 흑풍채, 산적
        |북쪽 산맥의 산적 소굴.
        |
        |## 청운객잔
        |키워드: 청운객잔, 객잔
        |교역로 한가운데의 객잔.
        |
        |## 무림맹
        |키워드: 무림맹, 정파
        |정파 연합.
        |""".trimMargin()

    private fun write(text: String = book): Path = Files.writeString(dir.resolve("keywords.md"), text)

    private fun keywordBook(maxActive: Int = 3) = KeywordBook(KeywordMatcher(), PromptProperties(keywordMaxActive = maxActive))

    private fun contribute(recentText: String, maxActive: Int = 3): String? =
        KeywordBookContributor(keywordBook(maxActive)).contribute(PromptContext(1L, dir, recentText, recentText, null))

    @Test
    fun `KEYWORDS 슬롯, 섹션 이름은 keyword_book`() {
        val c = KeywordBookContributor(keywordBook())
        assertThat(c.slot).isEqualTo(PromptSlot.KEYWORDS)
        assertThat(c.order).isEqualTo(0)
        assertThat(c.name).isEqualTo("keyword_book")
    }

    @Test
    fun `언급된 키워드의 설정을 제목과 함께 넣는다`() {
        write()

        val out = contribute("산적들이 길을 막았다")

        assertThat(out).isEqualTo("=== 키워드 설정 ===\n### 흑풍채\n북쪽 산맥의 산적 소굴.")
    }

    @Test
    fun `조사가 붙어도 발동하고, 별칭 키로도 발동한다`() {
        write()

        assertThat(keywordBook().select(dir, "마교의 무리가 나타났다").map { it.id }).containsExactly("천마신교")
        assertThat(keywordBook().select(dir, "청운객잔에서 쉬었다").map { it.id }).containsExactly("청운객잔")
    }

    @Test
    fun `언급되지 않은 항목은 넣지 않는다`() {
        write()

        val out = contribute("정파 사람과 객잔에 들렀다")!!

        assertThat(out).contains("### 청운객잔", "### 무림맹")
        assertThat(out).doesNotContain("천마신교", "흑풍채")
        assertThat(contribute("아무 일도 없었다")).isNull()
    }

    @Test
    fun `여러 개가 발동하면 텍스트 순서가 아니라 파일 순서(위가 우선)로 넣는다`() {
        write()

        val out = contribute("객잔에서 산적 이야기를 듣다가 마교 소문을 들었다")!!

        assertThat(listOf("### 천마신교", "### 흑풍채", "### 청운객잔").map { out.indexOf(it) }).allMatch { it >= 0 }.isSorted
        assertThat(out).contains("### 흑풍채\n북쪽 산맥의 산적 소굴.\n\n### 청운객잔")
    }

    @Test
    fun `동시 발동은 keyword-max-active개까지, 우선순위가 높은 것부터`() {
        write()
        val text = "무림맹과 마교가 객잔 앞에서 산적을 두고 다퉜다"

        assertThat(keywordBook(maxActive = 3).select(dir, text).map { it.id }).containsExactly("천마신교", "흑풍채", "청운객잔")
        assertThat(keywordBook(maxActive = 2).select(dir, text).map { it.id }).containsExactly("천마신교", "흑풍채")
        assertThat(keywordBook(maxActive = 10).select(dir, text)).hasSize(4)
        assertThat(contribute(text, maxActive = 0)).isNull()
    }

    @Test
    fun `기본 최대 개수는 3이다`() {
        assertThat(PromptProperties().keywordMaxActive).isEqualTo(3)
    }

    @Test
    fun `파일이 없거나 비어 있으면 섹션을 생략한다`() {
        assertThat(contribute("산적이 나타났다")).isNull()
        assertThat(keywordBook().entries(dir)).isEmpty()

        write("")
        assertThat(contribute("산적이 나타났다")).isNull()

        write("# 키워드북\n머리말만 있다\n")
        assertThat(contribute("산적이 나타났다")).isNull()
    }

    @Test
    fun `keywords_md가 디렉터리면 예외 없이 빈 목록이다`() {
        Files.createDirectory(dir.resolve("keywords.md"))

        assertThat(contribute("산적이 나타났다")).isNull()
    }

    @Test
    fun `파일을 고치면 다음 턴에 바로 반영된다`() {
        val file = write()
        val kb = keywordBook()
        assertThat(kb.select(dir, "산적").map { it.content }).containsExactly("북쪽 산맥의 산적 소굴.")

        Files.writeString(file, "## 흑풍채\n키워드: 산적\n소굴이 불탔다. 남은 산적은 서른 명.\n")
        Files.setLastModifiedTime(file, FileTime.fromMillis(Files.getLastModifiedTime(file).toMillis() + 5_000))

        assertThat(kb.select(dir, "산적").map { it.content }).containsExactly("소굴이 불탔다. 남은 산적은 서른 명.")

        Files.delete(file)
        assertThat(kb.select(dir, "산적")).isEmpty()
    }

    @Test
    fun `스토리 폴더가 다르면 각자의 키워드북을 쓴다`() {
        val other = Files.createDirectory(dir.resolve("other"))
        write()
        Files.writeString(other.resolve("keywords.md"), "## 흑풍채\n키워드: 산적\n다른 스토리의 흑풍채.\n")
        val kb = keywordBook()

        assertThat(kb.select(dir, "산적").single().content).isEqualTo("북쪽 산맥의 산적 소굴.")
        assertThat(kb.select(other, "산적").single().content).isEqualTo("다른 스토리의 흑풍채.")
    }

    @Test
    fun `템플릿 keywords_md는 안내 주석을 빼고 예시 항목만 읽힌다`() {
        val template = Files.readString(Path.of("..", "data", "_templates", "keywords.md"))

        val entries = KeywordBookParser.parse(template)

        assertThat(entries.map { it.id }).containsExactly("천마신교", "청운객잔")
        assertThat(entries[0].keys).containsExactly("천마신교", "마교", "신교")
        assertThat(entries.map { it.content }).noneMatch { it.contains("<!--") || it.contains("-->") }
    }
}
