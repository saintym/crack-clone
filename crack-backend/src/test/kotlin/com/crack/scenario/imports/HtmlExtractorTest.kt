package com.crack.scenario.imports

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** 추출기 (DESIGN.md §11.1): 스크립트 데이터 보존, 이미지 URL 수집, 상한 처리 */
class HtmlExtractorTest {

    private fun extractor(
        maxContextChars: Int = 150_000,
        maxDataBlockChars: Int = 40_000,
    ) = HtmlExtractor(ImportProperties(maxContextChars = maxContextChars, maxDataBlockChars = maxDataBlockChars))

    private val page = """
        <!doctype html>
        <html><head><title>무림 설정집</title>
        <style>body { color: red; }</style>
        </head>
        <body>
        <h1>강호에 부는 바람</h1>
        <p>정파와 마교가 &quot;십 년&quot;째 대치한다.</p>
        <img src="/MG/img/26.png" alt="휘령">
        <img src="img/relative.webp">
        <img src="data:image/png;base64,AAAA">
        <a href="https://cdn.example.com/scene.jpg">장면</a>
        <script>
        const chars = [
          {name:"휘령", alias:"낙화검선", img:"https://jangsue.uk/MG/img/26.png", vignette:"a } b ] c"},
          {name:"혜연", alias:"신권나찰", img:"https://jangsue.uk/MG/img/24.png"},
        ];
        const FN={murim:"무림맹"};
        function draw(){
          const local = [1,2,3];
        }
        </script>
        </body></html>
    """.trimIndent()

    @Test
    fun `본문 텍스트는 스크립트와 스타일을 뺴고 엔티티를 풀어 준다`() {
        val result = extractor().extract("https://jangsue.uk/MG/web.html", page)

        assertThat(result.title).isEqualTo("무림 설정집")
        assertThat(result.bodyText).contains("강호에 부는 바람")
        assertThat(result.bodyText).contains("정파와 마교가 \"십 년\"째 대치한다.")
        assertThat(result.bodyText).doesNotContain("const chars")
        assertThat(result.bodyText).doesNotContain("color: red")
        assertThat(result.bodyText).doesNotContain("<p>")
    }

    @Test
    fun `데이터 블록은 원문 그대로 잘라 오고 함수 안의 지역 변수는 빼놓는다`() {
        val result = extractor().extract("https://jangsue.uk/MG/web.html", page)

        assertThat(result.dataBlocks.map { it.name }).containsExactly("chars", "FN")
        val chars = result.dataBlocks.first().text
        // 문자열 안의 } 와 ] 에 속지 않고 배열 끝까지 가져온다
        assertThat(chars).startsWith("const chars = [")
        assertThat(chars).endsWith("]")
        assertThat(chars).contains("낙화검선")
        assertThat(chars).contains("a } b ] c")
        assertThat(chars).contains("혜연")
    }

    @Test
    fun `이미지 URL은 절대 주소로 정규화하고 data 스킴은 버린다`() {
        val result = extractor().extract("https://jangsue.uk/MG/web.html", page)

        assertThat(result.imageUrls).containsExactly(
            "https://jangsue.uk/MG/img/26.png",
            "https://jangsue.uk/MG/img/relative.webp",
            "https://cdn.example.com/scene.jpg",
            "https://jangsue.uk/MG/img/24.png",
        )
        assertThat(result.imageUrls).noneMatch { it.startsWith("data:") }
    }

    @Test
    fun `base64 데이터 블록은 버리고 몇 개를 버렸는지 적는다`() {
        val html = """
            <script>
            const EMBLEMS = {"ha": "data:image/png;base64,${"A".repeat(500)}"};
            const ORGS = [{name:"백련"}];
            </script>
        """.trimIndent()

        val result = extractor().extract("https://e.com/a.html", html)

        assertThat(result.dataBlocks.map { it.name }).containsExactly("ORGS")
        assertThat(result.truncated.droppedBlocks).isEqualTo(1)
    }

    @Test
    fun `합계 상한을 넘으면 데이터 블록을 남기고 본문을 앞에서부터 잘라 쓴다`() {
        val body = "가".repeat(5_000)
        val html = """
            <body><p>$body</p></body>
            <script>
            const DATA = [{a:"${"b".repeat(500)}"}];
            </script>
        """.trimIndent()

        val result = extractor(maxContextChars = 1_000).extract("https://e.com/a.html", html)

        // 데이터 블록이 먼저 살아남는다
        assertThat(result.dataBlocks).hasSize(1)
        assertThat(result.truncated.dataChars).isGreaterThan(500)
        // 남은 예산만큼만 본문이 들어간다
        assertThat(result.bodyText.length).isEqualTo(1_000 - result.truncated.dataChars)
        assertThat(result.truncated.droppedBodyChars).isGreaterThan(4_000)
        assertThat(result.truncated.truncated).isTrue()
        assertThat(result.context()).contains("잘린 부분")
    }

    @Test
    fun `데이터 블록 하나가 블록 상한을 넘으면 꼬리를 자른다`() {
        val html = """<script>
const BIG = [{a:"${"x".repeat(2_000)}"}];
</script>"""

        val result = extractor(maxDataBlockChars = 300).extract("https://e.com/a.html", html)

        assertThat(result.dataBlocks).hasSize(1)
        assertThat(result.dataBlocks.first().text).hasSizeLessThan(400)
        assertThat(result.dataBlocks.first().text).endsWith("(이하 생략) */")
    }

    @Test
    fun `데이터 블록 상한을 넘겨 버려진 블록도 droppedBlocks에 센다`() {
        val html = """<script>
const A = [{a:"${"x".repeat(2_000)}"}];
const B = [{b:1}];
</script>"""

        val result = extractor(maxContextChars = 100, maxDataBlockChars = 40_000).extract("https://e.com/a.html", html)

        assertThat(result.dataBlocks.map { it.name }).containsExactly("B")
        assertThat(result.truncated.droppedBlocks).isEqualTo(1)
    }

    @Test
    fun `컨텍스트에는 데이터 블록 원문과 본문이 모두 들어간다`() {
        val context = extractor().extract("https://jangsue.uk/MG/web.html", page).context()

        assertThat(context).contains("https://jangsue.uk/MG/web.html")
        assertThat(context).contains("### chars")
        assertThat(context).contains("낙화검선")
        assertThat(context).contains("강호에 부는 바람")
    }
}
