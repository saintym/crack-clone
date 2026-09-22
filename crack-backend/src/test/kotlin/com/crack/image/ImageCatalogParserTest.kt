package com.crack.image

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** `images.md` 파서 (DESIGN.md §8.5) */
class ImageCatalogParserTest {

    @Test
    fun `태그, URL, 설명을 읽고 항목이 아닌 줄은 무시한다`() {
        val text = """
            # 이미지 카탈로그
            안내문: 이 줄은 무시한다

            - 설월_미소: https://example.com/a.webp | 설월이 옅게 웃는 모습
            * 무극_분노 : http://example.com/b.png|  무극이 화난 모습  
            - 객잔: https://example.com/c.webp
        """.trimIndent()

        assertThat(ImageCatalogParser.parse(text)).containsExactly(
            ImageEntry("설월_미소", "https://example.com/a.webp", "설월이 옅게 웃는 모습"),
            ImageEntry("무극_분노", "http://example.com/b.png", "무극이 화난 모습"),
            ImageEntry("객잔", "https://example.com/c.webp", ""),
        )
    }

    @Test
    fun `http와 https가 아닌 URL은 버린다`() {
        val text = """
            - a: javascript:alert(1) | 스크립트
            - b: data:image/png;base64,AAAA
            - c: file:///etc/passwd
            - d: //example.com/d.webp
            - e: ftp://example.com/e.webp
            - f: https:///no-host.webp
            - g: https://example.com/a b.webp
            - h: https://example.com/"onerror=alert(1).webp
            - i: HTTPS://Example.com/ok.webp
        """.trimIndent()

        assertThat(ImageCatalogParser.parse(text).map { it.tag }).containsExactly("i")
    }

    @Test
    fun `태그에 공백이나 중괄호가 있으면 버린다`() {
        val text = """
            - 설월 미소: https://example.com/a.webp
            - 설월}}: https://example.com/b.webp
            - : https://example.com/c.webp
            - {{img: https://example.com/d.webp
            -설월: https://example.com/e.webp
        """.trimIndent()

        assertThat(ImageCatalogParser.parse(text)).isEmpty()
    }

    @Test
    fun `같은 태그는 위에 있는 것이 우선한다`() {
        val text = """
            - 설월: https://example.com/first.webp | 먼저
            - 설월: https://example.com/second.webp | 나중
        """.trimIndent()

        assertThat(ImageCatalogParser.parse(text)).containsExactly(
            ImageEntry("설월", "https://example.com/first.webp", "먼저"),
        )
    }

    @Test
    fun `설명 안의 파이프와 콜론은 그대로 둔다`() {
        val entry = ImageCatalogParser.parseLine("- 전투: https://example.com/x.webp?a=1 | 설명: 칼 | 방패")
        assertThat(entry).isEqualTo(ImageEntry("전투", "https://example.com/x.webp?a=1", "설명: 칼 | 방패"))
    }

    @Test
    fun `HTML 주석 안의 항목은 읽지 않는다`() {
        val text = """
            <!--
            예시:
            - 예시: https://example.com/example.webp
            -->
            - 켜짐: https://example.com/on.webp
            <!-- - 꺼짐: https://example.com/off.webp -->
        """.trimIndent()

        assertThat(ImageCatalogParser.parse(text).map { it.tag }).containsExactly("켜짐")
    }

    @Test
    fun `템플릿 파일은 항목이 없다`() {
        val template = java.nio.file.Path.of("../data/_templates/images.md")
        if (!java.nio.file.Files.exists(template)) return
        assertThat(ImageCatalogParser.parse(java.nio.file.Files.readString(template))).isEmpty()
    }
}
