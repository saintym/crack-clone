package com.crack.image

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** 일괄 등록 명세 파서 (DESIGN.md §8.6, T41) */
class ImageBulkSpecParserTest {

    private val spec = """
        #형식: ![](https://example.com/{c}_{a}.png)

        # 캐릭터 코드
        *F01: 완안연
        *F02: 신소향

        # 행동
        *1: 기본/대화
        *2: 미소
        *3~4: 놀람
    """.trimIndent()

    @Test
    fun `주소 틀과 표를 읽어 조합을 모두 펼친다`() {
        val r = ImageBulkSpecParser.parse(spec)

        assertThat(r.warnings).isEmpty()
        assertThat(r.urlTemplate).isEqualTo("https://example.com/{c}_{a}.png")
        assertThat(r.characters.map { it.label }).containsExactly("완안연", "신소향")
        // 범위가 펼쳐져 행동은 4개다
        assertThat(r.actions.map { it.code }).containsExactly("1", "2", "3", "4")
        // 2명 × 4행동
        assertThat(r.entries).hasSize(8)
    }

    @Test
    fun `변형 이름은 슬래시 앞을 쓰고 설명은 라벨 원문을 쓴다`() {
        val r = ImageBulkSpecParser.parse(spec)
        val first = r.entries.first()

        assertThat(first.tag).isEqualTo("완안연_기본")
        assertThat(first.description).isEqualTo("기본/대화")
        assertThat(first.url).isEqualTo("https://example.com/F01_1.png")
    }

    @Test
    fun `같은 라벨이 겹치면 뒤에 번호를 붙인다`() {
        val r = ImageBulkSpecParser.parse(spec)
        val tags = r.entries.filter { it.tag.startsWith("완안연_") }.map { it.tag }

        // 3~4가 같은 라벨 「놀람」이므로 놀람, 놀람2가 된다
        assertThat(tags).containsExactly("완안연_기본", "완안연_미소", "완안연_놀람", "완안연_놀람2")
    }

    @Test
    fun `캐릭터 코드와 행동 코드가 주소에 들어간다`() {
        val r = ImageBulkSpecParser.parse(spec)
        val urls = r.entries.filter { it.tag.startsWith("신소향_") }.map { it.url }

        assertThat(urls).containsExactly(
            "https://example.com/F02_1.png",
            "https://example.com/F02_2.png",
            "https://example.com/F02_3.png",
            "https://example.com/F02_4.png",
        )
    }

    @Test
    fun `행동 구역이 여러 개여도 모두 합친다`() {
        val r = ImageBulkSpecParser.parse(
            spec + "\n\n# 추가 동작\n*9: 잠듦\n"
        )

        assertThat(r.actions.map { it.code }).contains("9")
        assertThat(r.entries.map { it.tag }).contains("완안연_잠듦", "신소향_잠듦")
    }

    @Test
    fun `공백은 밑줄로 바꾸고 태그에 못 쓰는 글자는 버린다`() {
        assertThat(ImageBulkSpecParser.variantName("놀란 얼굴")).isEqualTo("놀란_얼굴")
        assertThat(ImageBulkSpecParser.variantName("기본/대화")).isEqualTo("기본")
        assertThat(ImageBulkSpecParser.variantName("a:b|c{d}")).isEqualTo("a_b_c_d")
    }

    @Test
    fun `자리수가 있는 범위는 자리수를 맞춘다`() {
        assertThat(ImageBulkSpecParser.expandCodes("01~03")).containsExactly("01", "02", "03")
        assertThat(ImageBulkSpecParser.expandCodes("8-10")).containsExactly("8", "9", "10")
        assertThat(ImageBulkSpecParser.expandCodes("F01")).containsExactly("F01")
        // 거꾸로 된 범위는 그대로 둔다
        assertThat(ImageBulkSpecParser.expandCodes("5~3")).containsExactly("5~3")
    }

    @Test
    fun `주소 틀이 없으면 경고하고 항목을 만들지 않는다`() {
        val r = ImageBulkSpecParser.parse("# 캐릭터 코드\n*F01: 완안연\n\n# 행동\n*1: 기본")

        assertThat(r.entries).isEmpty()
        assertThat(r.warnings).anyMatch { it.contains("주소 틀") }
    }

    @Test
    fun `자리표시자가 빠지면 경고한다`() {
        val r = ImageBulkSpecParser.parse(
            "#형식: https://example.com/{c}.png\n\n# 캐릭터 코드\n*F01: 완안연\n\n# 행동\n*1: 기본"
        )

        assertThat(r.warnings).anyMatch { it.contains("{a}") }
    }

    @Test
    fun `표가 비면 경고한다`() {
        val r = ImageBulkSpecParser.parse("#형식: ![](https://example.com/{c}_{a}.png)")

        assertThat(r.warnings).anyMatch { it.contains("캐릭터 목록") }
        assertThat(r.warnings).anyMatch { it.contains("행동·상황 목록") }
        assertThat(r.entries).isEmpty()
    }

    @Test
    fun `만든 태그는 카탈로그 파서가 그대로 읽는다`() {
        val r = ImageBulkSpecParser.parse(spec)
        val md = r.entries.joinToString("\n") { "- ${it.tag}: ${it.url} | ${it.description}" }

        val reparsed = ImageCatalogParser.parse(md)

        assertThat(reparsed.map { it.tag }).isEqualTo(r.entries.map { it.tag })
    }

    // ── 칸을 따로 받는 입구 (T42) ──

    @Test
    fun `칸을 따로 받아도 같은 결과가 나온다`() {
        val fields = ImageBulkSpecParser.parseFields(
            "https://example.com/{c}_{a}.png",
            "F01: 완안연\nF02: 신소향",
            "1: 기본/대화\n2: 미소\n3~4: 놀람",
        )
        val combined = ImageBulkSpecParser.parse(spec)

        assertThat(fields.warnings).isEmpty()
        assertThat(fields.entries).isEqualTo(combined.entries)
    }

    @Test
    fun `글머리표와 빈 줄과 주석 줄을 건너뛴다`() {
        val r = ImageBulkSpecParser.parseFields(
            "https://example.com/{c}_{a}.png",
            "# 캐릭터\n*F01: 완안연\n\n- F02: 신소향\n",
            "+ 1: 기본",
        )

        assertThat(r.warnings).isEmpty()
        assertThat(r.characters.map { it.label }).containsExactly("완안연", "신소향")
        assertThat(r.entries.map { it.tag }).containsExactly("완안연_기본", "신소향_기본")
    }

    @Test
    fun `주소 틀이 마크다운 이미지여도 주소만 꺼낸다`() {
        val r = ImageBulkSpecParser.parseFields(
            "![](https://example.com/{c}_{a}.png)",
            "F01: 가",
            "1: 기본",
        )

        assertThat(r.urlTemplate).isEqualTo("https://example.com/{c}_{a}.png")
    }

    @Test
    fun `형식이 틀린 줄만 건너뛰고 나머지는 살린다`() {
        val r = ImageBulkSpecParser.parseFields(
            "https://example.com/{c}_{a}.png",
            "F01: 가\n이건 콜론이 없다\nF02: 나",
            "1: 기본",
        )

        assertThat(r.characters.map { it.label }).containsExactly("가", "나")
        assertThat(r.warnings).anyMatch { it.contains("캐릭터 줄을 읽지 못해") }
    }

    @Test
    fun `칸이 비면 어느 칸인지 알려 준다`() {
        val r = ImageBulkSpecParser.parseFields("", "", "")

        assertThat(r.warnings).anyMatch { it.contains("주소 틀이 비어 있습니다") }
        assertThat(r.warnings).anyMatch { it.contains("캐릭터 목록이 비어 있습니다") }
        assertThat(r.warnings).anyMatch { it.contains("행동·상황 목록이 비어 있습니다") }
    }
}
