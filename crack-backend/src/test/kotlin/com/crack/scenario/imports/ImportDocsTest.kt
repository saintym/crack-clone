package com.crack.scenario.imports

import com.crack.chat.flow.EmotionTagFilter
import com.crack.image.ImageCatalogParser
import com.crack.memory.docs.CharacterDoc
import com.crack.memory.docs.ProtagonistDoc
import com.crack.prompt.keyword.KeywordBookParser
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.LocalDate

/**
 * 생성 문서가 **우리 파서로 읽히는지** (DESIGN.md §11.3).
 * 이것이 깨지면 기억 기록과 상태 패널이 조용히 망가진다.
 */
class ImportDocsTest {

    private val source = ImportDocs.sourceLine("https://jangsue.uk/MG/web.html", LocalDate.of(2026, 9, 25))

    @Test
    fun `인물 문서는 CharacterDoc으로 읽히고 기억 섹션이 비어 있다`() {
        val body = """
            # 캐릭터: 휘령
            ## 기본 정보
            - **이름**: 휘령
            - **별칭**: 낙화검선, 매화검
            - **나이**: 25
            - **성별**: 여
            ## 외모
            매화빛 도포.
            ## 말투
            존댓말.
        """.trimIndent()

        val text = ImportDocs.character("휘령", "휘령", body, source)
        val doc = CharacterDoc.parse("휘령", text)

        assertThat(doc.parseAliases()).containsExactly("낙화검선", "매화검")
        assertThat(doc.memorySection).isNotNull() // 섹션은 있고
        assertThat(doc.memoryLength()).isZero() // 내용은 비어 있다
        assertThat(doc.memory().isEmpty()).isTrue()
        assertThat(text).contains("출처: https://jangsue.uk/MG/web.html")
        assertThat(text.trimEnd()).endsWith("## 기억")
    }

    @Test
    fun `LLM이 기억 섹션을 채워 보내도 비워서 저장한다`() {
        val body = """
            # 캐릭터: 설월
            ## 기본 정보
            - **이름**: 설월
            ## 기억
            ### 관계
            - 주인공: 신뢰 (t3)
        """.trimIndent()

        val doc = CharacterDoc.parse("설월", ImportDocs.character("설월", "설월", body, source))

        assertThat(doc.memoryLength()).isZero()
        assertThat(doc.text).doesNotContain("주인공: 신뢰")
    }

    @Test
    fun `이름 줄이 없으면 넣어 준다`() {
        val body = """
            # 캐릭터: 혜연
            ## 외모
            승복 차림.
        """.trimIndent()

        val doc = CharacterDoc.parse("혜연", ImportDocs.character("혜연", "혜연", body, source))

        assertThat(com.crack.memory.docs.MemoryDocs.parseField(doc.text, "이름")).isEqualTo("혜연")
        assertThat(doc.text).contains("## 기본 정보")
    }

    @Test
    fun `파일명을 다듬은 인물은 원래 이름이 별칭에 들어간다`() {
        val body = "# 캐릭터: 백 자현\n## 기본 정보\n- **이름**: 백 자현\n"

        val doc = CharacterDoc.parse("백_자현", ImportDocs.character("백_자현", "백 자현", body, source))

        assertThat(doc.parseAliases()).contains("백 자현")
    }

    @Test
    fun `주인공 문서는 ProtagonistDoc으로 읽히고 변화 기록이 비어 있다`() {
        val body = """
            # 주인공 (사용자)
            ## 기본 정보
            - **이름**:
            - **나이**: 22
            ## 배경
            무명의 낭인.
            ## 변화 기록
            - 아무 것이나
        """.trimIndent()

        val doc = ProtagonistDoc.parse(ImportDocs.protagonist("무명", body, source))

        assertThat(doc.displayName()).isEqualTo("무명")
        assertThat(doc.changesSection).isNotNull()
        assertThat(doc.changesLength()).isZero()
        assertThat(doc.changes().isEmpty()).isTrue()
        assertThat(doc.text).doesNotContain("아무 것이나")
    }

    @Test
    fun `키워드북은 KeywordBookParser로 읽히고 키워드 줄이 보장된다`() {
        val body = """
            ## 천마신교
            키워드: 천마신교, 마교, 신교
            중원 서쪽의 마도 문파.

            ## 청운객잔
            교역로 한가운데의 객잔.

            ## 빈 항목
        """.trimIndent()

        val text = ImportDocs.keywords(body, source)
        val entries = KeywordBookParser.parse(text)

        assertThat(entries.map { it.id }).containsExactly("천마신교", "청운객잔")
        assertThat(entries[0].keys).containsExactly("천마신교", "마교", "신교")
        assertThat(entries[1].keys).containsExactly("청운객잔") // 키워드 줄이 없으면 제목을 키로 넣는다
        assertThat(entries[1].content).contains("교역로")
        assertThat(text).contains("키워드: 청운객잔")
    }

    @Test
    fun `이미지 카탈로그는 ImageCatalogParser로 읽히고 인물 태그가 기본 규칙을 따른다`() {
        val text = ImportDocs.images(
            characterImages = listOf("휘령" to "https://jangsue.uk/MG/img/26.png", "혜연" to "https://jangsue.uk/MG/img/24.png"),
            sceneUrls = listOf("https://jangsue.uk/MG/img/bg.png"),
            source = source,
        )
        val entries = ImageCatalogParser.parse(text)

        assertThat(entries.map { it.tag }).containsExactly("휘령_기본", "혜연_기본")
        assertThat(entries[0].url).isEqualTo("https://jangsue.uk/MG/img/26.png")
        assertThat(entries[0].description).isEqualTo("휘령의 기본 이미지")
        // 인물과 맞지 않는 이미지는 주석 안에 있어 파서가 읽지 않는다
        assertThat(text).contains("https://jangsue.uk/MG/img/bg.png")
        assertThat(entries.map { it.url }).doesNotContain("https://jangsue.uk/MG/img/bg.png")
    }

    @Test
    fun `미분류 이미지는 줄마다 따로 주석으로 남기고 태그 이름을 넣지 않는다`() {
        val text = ImportDocs.images(
            characterImages = listOf("휘령" to "https://e.com/1.png"),
            sceneUrls = listOf("https://e.com/a.png", "https://e.com/b.png", "https://e.com/c.png"),
            source = source,
        )

        // BUG-024: 같은 태그 이름을 여러 줄에 넣으면 주석을 풀 때 첫 줄만 인식된다
        assertThat(text).doesNotContain("태그를_정하세요")
        assertThat(text).contains("<!-- 미분류 이미지 1: https://e.com/a.png — 쓰려면 주석을 풀고 태그 이름을 정하세요 -->")
        assertThat(text).contains("<!-- 미분류 이미지 3: https://e.com/c.png — 쓰려면 주석을 풀고 태그 이름을 정하세요 -->")
        // 주석 줄마다 자기 줄에서 닫힌다 → 한 줄만 풀어도 나머지는 그대로 주석이다
        text.lines().filter { it.startsWith("<!-- 미분류") }.forEach { assertThat(it).endsWith("-->") }
        assertThat(ImageCatalogParser.parse(text).map { it.tag }).containsExactly("휘령_기본")

        // 한 줄을 풀어 태그를 정하면 그 줄만 읽힌다
        val edited = text.replace(
            "<!-- 미분류 이미지 2: https://e.com/b.png — 쓰려면 주석을 풀고 태그 이름을 정하세요 -->",
            "- 객잔_밤: https://e.com/b.png | 밤의 객잔",
        )
        assertThat(ImageCatalogParser.parse(edited).map { it.tag }).containsExactly("휘령_기본", "객잔_밤")
    }

    @Test
    fun `프롤로그 첫 줄 인물 태그를 인물 파일명으로 맞춘다`() {
        val names = mapOf("휘 령" to "휘_령", "혜연" to "혜연")

        val text = ImportDocs.prologue("[인물: 휘 령]\n\n*비가 그친 저녁.*\n", source, names)

        assertThat(text).startsWith("[인물: 휘_령]\n")
        assertThat(text).contains("*비가 그친 저녁.*")
        assertThat(EmotionTagFilter.parse(text).tags.speaker).isEqualTo("휘_령")
    }

    @Test
    fun `프롤로그 태그의 변형은 지우고 감정 태그는 남긴다`() {
        val text = ImportDocs.prologue(
            "[감정: 경계심] [인물: 혜연/미소]\n\n*문이 열렸다.*\n", source, mapOf("혜연" to "혜연"),
        )

        assertThat(text).startsWith("[감정: 경계심] [인물: 혜연]\n")
        val parsed = EmotionTagFilter.parse(text)
        assertThat(parsed.tags.speaker).isEqualTo("혜연")
        assertThat(parsed.tags.speakerVariant).isNull()
        assertThat(parsed.tags.emotion).isEqualTo("경계심")
    }

    @Test
    fun `모르는 인물 태그는 지우고 태그가 없으면 본문을 그대로 둔다`() {
        val names = mapOf("혜연" to "혜연")

        val unknown = ImportDocs.prologue("[인물: 없는사람]\n\n*문이 열렸다.*\n", source, names)
        assertThat(unknown).startsWith("*문이 열렸다.*")
        assertThat(EmotionTagFilter.parse(unknown).tags.speaker).isNull()

        val noTag = ImportDocs.prologue("*문이 열렸다.*\n\n\"누구냐.\"\n", source, names)
        assertThat(noTag).startsWith("*문이 열렸다.*\n\n\"누구냐.\"")
    }

    @Test
    fun `코드 펜스로 감싼 응답도 벗겨 낸다`() {
        val body = "```markdown\n# 세계관\n## 시대\n무협.\n```"

        val text = ImportDocs.plain(body, source)

        assertThat(text).startsWith("# 세계관")
        assertThat(text).doesNotContain("```")
        assertThat(text.trimEnd()).endsWith("-->")
    }

    @Test
    fun `쓸 수 없는 이름을 걸러 내고 중복에 숫자를 붙인다`() {
        assertThat(ImportDocs.safeName("휘령")).isEqualTo("휘령")
        assertThat(ImportDocs.safeName("백 자현")).isEqualTo("백_자현")
        assertThat(ImportDocs.safeName("a/b")).isEqualTo("a_b")
        assertThat(ImportDocs.safeName("../etc")).isEqualTo("etc")
        assertThat(ImportDocs.safeName("..")).isNull()
        assertThat(ImportDocs.safeName("   ")).isNull()
        assertThat(ImportDocs.safeName("a|b:c{d}")).isEqualTo("a_b_c_d")

        val used = mutableSetOf<String>()
        assertThat(ImportDocs.uniqueName(used, "설월")).isEqualTo("설월")
        assertThat(ImportDocs.uniqueName(used, "설월")).isEqualTo("설월-2")
        assertThat(ImportDocs.uniqueName(used, "설월")).isEqualTo("설월-3")
    }
}
