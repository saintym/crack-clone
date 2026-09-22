package com.crack.prompt

import com.crack.ai.dto.MessageRole
import com.crack.prompt.service.LegacyPromptAssembler
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*
import java.nio.file.Files
import java.nio.file.Path

@Suppress("DEPRECATION")
class LegacyPromptAssemblerTest {

    private lateinit var assembler: LegacyPromptAssembler
    private lateinit var tempDir: Path
    private lateinit var scenarioPath: Path
    private lateinit var storyPath: Path

    @BeforeEach
    fun setUp() {
        tempDir = Files.createTempDirectory("crack-prompt-test")
        assembler = LegacyPromptAssembler()

        // 시나리오 디렉토리 (템플릿)
        scenarioPath = tempDir.resolve("scenario")
        Files.createDirectories(scenarioPath.resolve("characters"))

        // 스토리 디렉토리 (인스턴스)
        storyPath = tempDir.resolve("story")
        Files.createDirectories(storyPath.resolve("characters"))
        Files.createDirectories(storyPath.resolve("memory"))
        Files.createDirectories(storyPath.resolve("chat"))

        Files.writeString(storyPath.resolve("world.md"), """
            # 세계관
            ## 시대
            현대 서울, 2026년
            ## 장소
            한양대학교 캠퍼스
            ## 분위기
            밝고 활기찬 대학 생활
        """.trimIndent())

        Files.writeString(storyPath.resolve("scenario.md"), """
            # 시나리오
            ## 초기 상황
            새 학기 첫 날, 주인공이 강의실에 들어선다.
        """.trimIndent())

        Files.writeString(storyPath.resolve("characters/protagonist.md"), """
            # 주인공
            ## 기본 정보
            - 이름: 김민수
            - 나이: 22살
            ## 성격
            조용하고 내성적
        """.trimIndent())

        Files.writeString(storyPath.resolve("characters/하은.md"), """
            # 캐릭터: 하은
            ## 기본 정보
            - 이름: 하은
            - 나이: 21살
            ## 성격
            밝고 활발함, 츤데레
            ## 말투
            반말, 가끔 "~거든!" 으로 끝남
        """.trimIndent())

        Files.writeString(storyPath.resolve("characters/준호.md"), """
            # 캐릭터: 준호
            ## 기본 정보
            - 이름: 준호
            - 나이: 23살
            ## 성격
            쾌활하고 다정한 형 같은 존재
        """.trimIndent())

        Files.writeString(storyPath.resolve("user_note.md"), """
            # 유저노트
            - 하은은 3턴에서 주인공에게 이름을 가르쳐줬다
            - 주인공은 왼팔에 화상 흉터가 있다
        """.trimIndent())
    }

    @AfterEach
    fun tearDown() {
        Files.walk(tempDir)
            .sorted(Comparator.reverseOrder())
            .forEach { Files.deleteIfExists(it) }
    }

    // --- 시스템 프롬프트 조립 ---

    @Test
    fun `시스템 프롬프트에 기본 규칙이 포함된다`() {
        val prompt = assembler.assembleSystemPrompt(scenarioPath, storyPath)

        assertTrue(prompt.contains("롤플레이 AI 작가"), "기본 규칙이 포함되어야 한다")
        assertTrue(prompt.contains("감정 태그 형식"), "감정 태그 규칙이 포함되어야 한다")
    }

    @Test
    fun `시스템 프롬프트에 세계관이 포함된다`() {
        val prompt = assembler.assembleSystemPrompt(scenarioPath, storyPath)

        assertTrue(prompt.contains("현대 서울"), "세계관 내용이 포함되어야 한다")
        assertTrue(prompt.contains("한양대학교"), "세계관 장소가 포함되어야 한다")
    }

    @Test
    fun `시스템 프롬프트에 모든 캐릭터가 포함된다`() {
        val prompt = assembler.assembleSystemPrompt(scenarioPath, storyPath)

        assertTrue(prompt.contains("캐릭터: 하은"), "하은 캐릭터가 포함되어야 한다")
        assertTrue(prompt.contains("캐릭터: 준호"), "준호 캐릭터가 포함되어야 한다")
        assertTrue(prompt.contains("츤데레"), "하은의 성격이 포함되어야 한다")
    }

    @Test
    fun `특정 캐릭터만 지정하면 해당 캐릭터만 프롬프트에 포함된다`() {
        val prompt = assembler.assembleSystemPrompt(scenarioPath, storyPath, listOf("하은"))

        assertTrue(prompt.contains("캐릭터: 하은"), "하은이 포함되어야 한다")
        assertFalse(prompt.contains("캐릭터: 준호"), "준호는 포함되면 안 된다")
    }

    @Test
    fun `시스템 프롬프트에 주인공 설정이 포함된다`() {
        val prompt = assembler.assembleSystemPrompt(scenarioPath, storyPath)

        assertTrue(prompt.contains("주인공(사용자)"), "주인공 섹션이 있어야 한다")
        assertTrue(prompt.contains("김민수"), "주인공 이름이 포함되어야 한다")
    }

    @Test
    fun `시스템 프롬프트에 유저노트가 포함된다`() {
        val prompt = assembler.assembleSystemPrompt(scenarioPath, storyPath)

        assertTrue(prompt.contains("=== 유저노트 ==="), "유저노트 섹션이 있어야 한다")
        assertTrue(prompt.contains("왼팔에 화상 흉터"), "유저노트 내용이 포함되어야 한다")
    }

    // --- 스토리 격리 (D12) ---

    @Test
    fun `스토리 폴더만 읽고 시나리오 원본은 읽지 않는다`() {
        Files.writeString(scenarioPath.resolve("world.md"), "원본 세계관 ORIGINAL_WORLD")
        Files.writeString(scenarioPath.resolve("scenario.md"), "원본 시나리오 ORIGINAL_SCENARIO")
        Files.writeString(scenarioPath.resolve("characters/하은.md"), "원본 하은 ORIGINAL_HAEUN")
        Files.writeString(scenarioPath.resolve("characters/원본전용.md"), "# 캐릭터: 원본전용")
        Files.writeString(scenarioPath.resolve("characters/protagonist.md"), "원본 주인공 ORIGINAL_PROTAGONIST")

        val prompt = assembler.assembleSystemPrompt(scenarioPath, storyPath)

        assertFalse(prompt.contains("ORIGINAL_"), "시나리오 원본 내용이 섞이면 안 된다")
        assertFalse(prompt.contains("원본전용"), "원본에만 있는 인물은 넣지 않는다")
        assertTrue(prompt.contains("현대 서울") && prompt.contains("츤데레") && prompt.contains("김민수"))
    }

    @Test
    fun `스토리 폴더에 파일이 없어도 시나리오로 폴백하지 않는다`() {
        Files.delete(storyPath.resolve("world.md"))
        Files.delete(storyPath.resolve("characters/protagonist.md"))
        Files.writeString(scenarioPath.resolve("world.md"), "원본 세계관 ORIGINAL_WORLD")
        Files.writeString(scenarioPath.resolve("characters/protagonist.md"), "원본 주인공 ORIGINAL_PROTAGONIST")

        val prompt = assembler.assembleSystemPrompt(scenarioPath, storyPath, listOf("하은", "없는인물"))

        assertFalse(prompt.contains("=== 세계관 ==="))
        assertFalse(prompt.contains("=== 주인공(사용자) ==="))
        assertFalse(prompt.contains("ORIGINAL_"))
        assertFalse(prompt.contains("없는인물"))
    }

    @Test
    fun `user_note가 없는 옛 스토리는 같은 폴더의 must_remember를 읽는다`() {
        Files.delete(storyPath.resolve("user_note.md"))
        Files.writeString(storyPath.resolve("memory/must_remember.md"), "옛 기억 LEGACY_NOTE")

        val prompt = assembler.assembleSystemPrompt(scenarioPath, storyPath)

        assertTrue(prompt.contains("LEGACY_NOTE"))
    }

    @Test
    fun `user_note가 있으면 must_remember는 읽지 않는다`() {
        Files.writeString(storyPath.resolve("memory/must_remember.md"), "옛 기억 LEGACY_NOTE")

        val prompt = assembler.assembleSystemPrompt(scenarioPath, storyPath)

        assertFalse(prompt.contains("LEGACY_NOTE"))
    }

    @Test
    fun `인물 이름으로 characters 밖의 파일을 읽을 수 없다`() {
        Files.writeString(tempDir.resolve("secret.md"), "SECRET")
        Files.writeString(storyPath.resolve("world2.md"), "SECRET2")

        val prompt = assembler.assembleSystemPrompt(scenarioPath, storyPath, listOf("../../secret", "../world2", "하은"))

        assertFalse(prompt.contains("SECRET"))
        assertTrue(prompt.contains("캐릭터: 하은"))
    }

    @Test
    fun `시스템 프롬프트에 출력 형식이 포함된다`() {
        val prompt = assembler.assembleSystemPrompt(scenarioPath, storyPath)

        assertTrue(prompt.contains("출력 형식"), "출력 형식 규칙이 포함되어야 한다")
        assertTrue(prompt.contains("[감정:"), "감정 태그 예시가 포함되어야 한다")
    }

    // --- 대화 컨텍스트 로드 ---

    @Test
    fun `chat_latest에서 대화 히스토리를 파싱한다`() {
        Files.writeString(storyPath.resolve("chat/chat_latest.md"), """
            # 최근 대화

            ## USER
            안녕하세요

            ## ASSISTANT
            [감정: 놀람]
            *눈을 동그랗게 뜨며 돌아본다*
            "어, 안녕...?"

            ## USER
            이름이 뭐야?
        """.trimIndent())

        val messages = assembler.loadConversationContext(storyPath)

        assertEquals(3, messages.size, "대화 메시지 3개가 파싱되어야 한다")
        assertEquals(MessageRole.USER, messages[0].role)
        assertTrue(messages[0].content.contains("안녕하세요"))
        assertEquals(MessageRole.ASSISTANT, messages[1].role)
        assertTrue(messages[1].content.contains("어, 안녕...?"))
        assertEquals(MessageRole.USER, messages[2].role)
        assertTrue(messages[2].content.contains("이름이 뭐야"))
    }

    @Test
    fun `요약 파일이 있으면 대화 컨텍스트에 요약이 포함된다`() {
        Files.writeString(storyPath.resolve("memory/summary_001_010.md"), """
            # 1~10턴 요약
            주인공이 하은을 처음 만났다. 하은은 처음엔 경계했지만 점점 마음을 열었다.
        """.trimIndent())

        Files.writeString(storyPath.resolve("chat/chat_latest.md"), """
            # 최근 대화

            ## USER
            오늘 날씨 좋다
        """.trimIndent())

        val messages = assembler.loadConversationContext(storyPath)

        // 요약 (user + assistant) + 최근 대화 1개 = 3
        assertTrue(messages.size >= 2, "요약 + 최근 대화가 포함되어야 한다")
        assertTrue(messages[0].content.contains("이전 대화 요약"), "요약 헤더가 있어야 한다")
        assertTrue(messages[0].content.contains("하은을 처음 만났다"), "요약 내용이 포함되어야 한다")
    }

    @Test
    fun `요약 파일이 5개를 넘으면 최근 5개만 로드한다`() {
        val memoryDir = storyPath.resolve("memory")
        for (i in 1..7) {
            val start = (i - 1) * 10 + 1
            val end = i * 10
            val fileName = "summary_%03d_%03d.md".format(start, end)
            Files.writeString(memoryDir.resolve(fileName), "# ${start}~${end}턴 요약\n요약 내용 $i")
        }

        val summaries = assembler.loadSummaries(storyPath)

        // 7개 중 최근 5개만 (31-40, 41-50, 51-60, 61-70)
        assertFalse(summaries.contains("요약 내용 1"), "1번째 요약은 포함되면 안 된다")
        assertFalse(summaries.contains("요약 내용 2"), "2번째 요약은 포함되면 안 된다")
        assertTrue(summaries.contains("요약 내용 3"), "3번째 요약은 포함되어야 한다")
        assertTrue(summaries.contains("요약 내용 7"), "7번째 요약은 포함되어야 한다")
    }

    @Test
    fun `빈 chat_latest 파일이면 대화 메시지가 비어있다`() {
        Files.writeString(storyPath.resolve("chat/chat_latest.md"), "# 최근 대화\n\n")

        val messages = assembler.loadConversationContext(storyPath)

        assertTrue(messages.isEmpty(), "빈 대화 파일이면 메시지가 없어야 한다")
    }

    // --- parseChatLatest 단위 테스트 ---

    @Test
    fun `parseChatLatest 여러 턴 파싱`() {
        val content = """
            # 최근 대화

            ## USER
            첫 번째 메시지

            ## ASSISTANT
            첫 번째 응답
            여러 줄 응답

            ## USER
            두 번째 메시지

            ## ASSISTANT
            두 번째 응답
        """.trimIndent()

        val messages = assembler.parseChatLatest(content)

        assertEquals(4, messages.size)
        assertEquals("첫 번째 메시지", messages[0].content)
        assertTrue(messages[1].content.contains("여러 줄 응답"))
        assertEquals("두 번째 메시지", messages[2].content)
        assertEquals("두 번째 응답", messages[3].content)
    }
}
