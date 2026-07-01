package com.crack.prompt.service

import com.crack.ai.dto.ChatMessage
import com.crack.ai.dto.MessageRole
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.nio.file.Files
import java.nio.file.Path

@Service
class PromptAssembler {
    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        private const val BASE_RULE = """당신은 몰입형 소설/롤플레이 AI 작가입니다. 아래 설정과 규칙을 반드시 준수하세요.

## 핵심 규칙
1. 설정된 캐릭터의 성격, 말투, 배경을 일관되게 유지하세요.
2. 주인공(사용자)의 행동이나 대사를 절대 대신 만들지 마세요.
3. 응답은 반드시 한국어로 하세요.
4. 응답은 최소 200자 이상, 풍부한 묘사와 감정 표현을 포함하세요.
5. 장면 전환, 시간 경과, 분위기 묘사를 세밀하게 작성하세요.

## 문체 규칙
- 행동/상황 묘사: *기울임*으로 감싸세요. 장면의 분위기, 캐릭터의 미세한 동작, 주변 환경 등을 상세히 묘사하세요.
- 대사: "큰따옴표"로 감싸세요. 캐릭터의 말투와 감정이 드러나도록 작성하세요.
- 내면 독백이나 생각: 별도 표시 없이 서술체로 작성하세요.
- 문단을 적절히 나누어 가독성을 높이세요. 묘사와 대사 사이에 빈 줄을 넣으세요."""

        private const val OUTPUT_FORMAT = """
## 출력 형식
응답의 맨 첫 줄에 감정 태그를 작성하고, 그 아래에 본문을 작성하세요.

감정 태그 형식: [감정: (현재 감정 1~3개)]

예시:
[감정: 경계심, 호기심]

*차가운 바람이 얼굴을 스치고 지나갔다. 그녀는 좁은 골목 끝에 서서 어둠 속을 응시했다. 미세한 발소리가 등 뒤에서 들려왔지만, 고개를 돌리지 않았다.*

*대신 손끝에 힘을 주어 품속의 단검 자루를 쥐었다.*

"누구야. 나와."

*낮고 단호한 목소리가 골목에 울렸다. 그제야 어둠 속에서 한 그림자가 천천히 모습을 드러냈다.*

"겁이 없군. 아니, 겁을 숨기는 법을 아는 건가."

*그림자의 주인은 입꼬리를 살짝 올리며 걸음을 멈추었다. 달빛 아래 드러난 얼굴에는 장난기와 위험이 동시에 서려 있었다.*
"""
        private const val MAX_SUMMARY_COUNT = 5
    }

    /**
     * 시스템 프롬프트 조립.
     * scenarioPath: 시나리오 템플릿 경로 (world.md, scenario.md, characters/)
     * storyPath: 스토리 데이터 경로 (memory/, chat/) - 캐릭터 오버라이드도 여기 우선
     */
    fun assembleSystemPrompt(scenarioPath: Path, storyPath: Path, activeCharacters: List<String>? = null): String {
        val parts = mutableListOf<String>()

        // 1. 기본 규칙
        parts.add(BASE_RULE)

        // 2. 세계관
        readFileIfExists(scenarioPath.resolve("world.md"))?.let { content ->
            parts.add("=== 세계관 ===\n$content")
        }

        // 3. 캐릭터 설정 (스토리 오버라이드 우선, 없으면 시나리오 기본)
        val baseCharDir = scenarioPath.resolve("characters")
        val storyCharDir = storyPath.resolve("characters")
        if (activeCharacters != null) {
            activeCharacters.forEach { charName ->
                val content = readFileIfExists(storyCharDir.resolve("$charName.md"))
                    ?: readFileIfExists(baseCharDir.resolve("$charName.md"))
                content?.let { parts.add("=== 캐릭터: $charName ===\n$it") }
            }
        } else if (Files.exists(baseCharDir)) {
            // 기본 캐릭터 목록에서 로드하되, 스토리 오버라이드 우선
            Files.list(baseCharDir)
                .filter { it.toString().endsWith(".md") && it.fileName.toString() != "protagonist.md" }
                .forEach { path ->
                    val charName = path.fileName.toString().removeSuffix(".md")
                    val content = readFileIfExists(storyCharDir.resolve("$charName.md"))
                        ?: Files.readString(path)
                    parts.add("=== 캐릭터: $charName ===\n$content")
                }
        }

        // 4. 주인공 설정 (스토리 오버라이드 우선)
        val protagonistContent = readFileIfExists(storyCharDir.resolve("protagonist.md"))
            ?: readFileIfExists(baseCharDir.resolve("protagonist.md"))
        protagonistContent?.let { parts.add("=== 주인공(사용자) ===\n$it") }

        // 5. 시나리오 초기 상황
        readFileIfExists(scenarioPath.resolve("scenario.md"))?.let { content ->
            parts.add("=== 시나리오 ===\n$content")
        }

        // 6. 필수 기억사항 (스토리에 저장)
        readFileIfExists(storyPath.resolve("memory/must_remember.md"))?.let { content ->
            parts.add("=== 필수 기억사항 ===\n$content")
        }

        // 7. 출력 형식
        parts.add(OUTPUT_FORMAT)

        return parts.joinToString("\n\n")
    }

    fun loadConversationContext(storyPath: Path): List<ChatMessage> {
        val messages = mutableListOf<ChatMessage>()

        // 요약 로드 (최근 N개만)
        val summaries = loadSummaries(storyPath)
        if (summaries.isNotBlank()) {
            messages.add(ChatMessage(
                role = MessageRole.USER,
                content = "[이전 대화 요약]\n$summaries"
            ))
            messages.add(ChatMessage(
                role = MessageRole.ASSISTANT,
                content = "네, 이전 대화 내용을 기억하겠습니다."
            ))
        }

        // 최근 대화 로드
        val recentChat = readFileIfExists(storyPath.resolve("chat/chat_latest.md"))
        if (recentChat != null && recentChat.isNotBlank()) {
            val parsedMessages = parseChatLatest(recentChat)
            messages.addAll(parsedMessages)
        }

        return messages
    }

    fun loadSummaries(storyPath: Path): String {
        val memoryDir = storyPath.resolve("memory")
        if (!Files.exists(memoryDir)) return ""

        val summaryFiles = Files.list(memoryDir)
            .filter { it.fileName.toString().startsWith("summary_") && it.fileName.toString().endsWith(".md") }
            .sorted(Comparator.comparing { it.fileName.toString() })
            .toList()

        // 최근 N개만
        val recentSummaries = summaryFiles.takeLast(MAX_SUMMARY_COUNT)
        return recentSummaries.joinToString("\n\n---\n\n") { Files.readString(it) }
    }

    fun parseChatLatest(content: String): List<ChatMessage> {
        val messages = mutableListOf<ChatMessage>()
        val lines = content.lines()
        var currentRole: MessageRole? = null
        val currentContent = StringBuilder()

        for (line in lines) {
            when {
                line.startsWith("## USER") || line.startsWith("## user") -> {
                    flushMessage(currentRole, currentContent, messages)
                    currentRole = MessageRole.USER
                    currentContent.clear()
                }
                line.startsWith("## ASSISTANT") || line.startsWith("## assistant") -> {
                    flushMessage(currentRole, currentContent, messages)
                    currentRole = MessageRole.ASSISTANT
                    currentContent.clear()
                }
                currentRole != null -> {
                    if (currentContent.isNotEmpty()) currentContent.append("\n")
                    currentContent.append(line)
                }
            }
        }
        flushMessage(currentRole, currentContent, messages)

        return messages
    }

    private fun flushMessage(role: MessageRole?, content: StringBuilder, messages: MutableList<ChatMessage>) {
        if (role != null && content.isNotBlank()) {
            messages.add(ChatMessage(role = role, content = content.toString().trim()))
        }
    }

    private fun readFileIfExists(path: Path): String? {
        return if (Files.exists(path)) {
            val content = Files.readString(path)
            if (content.isBlank()) null else content
        } else null
    }
}
