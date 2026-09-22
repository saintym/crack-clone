package com.crack.memory.service

import com.crack.ai.dto.AiPurpose
import com.crack.ai.dto.AiRequest
import com.crack.ai.dto.ChatMessage
import com.crack.ai.dto.MessageRole
import com.crack.ai.service.AiGateway
import com.crack.chat.service.ChatFileService
import com.crack.global.config.DataPaths
import com.crack.global.exception.NotFoundException
import com.crack.memory.dto.SummarizeResult
import com.crack.memory.dto.SummaryResponse
import com.crack.scenario.repository.ScenarioRepository
import com.crack.story.repository.StoryRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.nio.file.Files
import java.nio.file.Path

@Service
class MemoryService(
    private val aiGateway: AiGateway,
    private val chatFileService: ChatFileService,
    private val scenarioRepository: ScenarioRepository,
    private val storyRepository: StoryRepository,
    private val storySummaryService: StorySummaryService,
    private val dataPaths: DataPaths
) {
    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        private const val TURNS_PER_SUMMARY = 10

        private const val SUMMARY_PROMPT = """아래 대화 내용을 간결하게 요약해주세요.

요약 규칙:
1. 주요 사건, 감정 변화, 관계 변화를 중심으로 요약
2. 캐릭터별로 어떤 행동을 했고 어떤 감정을 보였는지 포함
3. 주인공(사용자)의 선택과 그 결과를 포함
4. 한국어로 작성
5. 3~5문단으로 요약"""

        private const val CHARACTER_UPDATE_PROMPT = """아래는 최근 대화 요약과 기존 캐릭터 문서입니다.
대화에서 발생한 변화를 기존 캐릭터 문서의 '주요 사건 기록' 섹션에 추가해주세요.

규칙:
1. 기존 내용은 유지하고 새로운 사건만 추가
2. 날짜/턴 번호와 함께 기록
3. 관계 변화, 감정 변화, 중요한 대화 내용을 포함
4. 원본 문서 형식(마크다운)을 유지
5. 전체 문서를 반환"""
    }

    // --- 필수 기억사항 ---

    fun readMustRemember(storyId: Long): String {
        val path = mustRememberPath(storyId)
        return if (Files.exists(path)) Files.readString(path) else ""
    }

    fun updateMustRemember(storyId: Long, content: String): String {
        val path = mustRememberPath(storyId)
        Files.createDirectories(path.parent)
        Files.writeString(path, content)
        return content
    }

    // --- 요약 ---

    fun listSummaries(storyId: Long): List<SummaryResponse> {
        val memoryDir = storyDir(storyId).resolve("memory")
        if (!Files.exists(memoryDir)) return emptyList()

        return Files.list(memoryDir)
            .filter { it.fileName.toString().startsWith("summary_") && it.fileName.toString().endsWith(".md") }
            .sorted(Comparator.comparing { it.fileName.toString() })
            .map { path ->
                val fileName = path.fileName.toString()
                val (from, to) = parseSummaryRange(fileName)
                SummaryResponse(
                    fileName = fileName,
                    fromTurn = from,
                    toTurn = to,
                    content = Files.readString(path)
                )
            }
            .toList()
    }

    fun summarize(storyId: Long): SummarizeResult {
        val story = storyRepository.findById(storyId)
            .orElseThrow { NotFoundException("스토리를 찾을 수 없습니다: $storyId") }
        val scenario = scenarioRepository.findById(story.scenarioId)
            .orElseThrow { NotFoundException("시나리오를 찾을 수 없습니다.") }

        val storyPath = dataPaths.storyDir(scenario.name, story.dirName)
        val turnCount = story.turnCount
        val fromTurn = ((turnCount - 1) / TURNS_PER_SUMMARY) * TURNS_PER_SUMMARY + 1
        val toTurn = fromTurn + TURNS_PER_SUMMARY - 1

        // 1. 현재 대화 내용 읽기
        val chatContent = chatFileService.readChatLatest(storyPath)

        // 2. Claude에게 요약 요청
        val summary = requestSummary(chatContent)

        // 3. 요약 파일 저장
        val summaryFileName = "summary_%03d_%03d.md".format(fromTurn, toTurn)
        val summaryContent = "# ${fromTurn}~${toTurn}턴 요약\n\n$summary"
        val memoryDir = storyPath.resolve("memory")
        Files.createDirectories(memoryDir)
        Files.writeString(memoryDir.resolve(summaryFileName), summaryContent)

        // 4. DB에 L1 요약 저장 (다단계 요약 트리거)
        try {
            storySummaryService.saveL1Summary(storyId, scenario.id, fromTurn, toTurn, summary)
        } catch (e: Exception) {
            log.warn("DB 요약 저장 실패 (파일 요약은 정상): ${e.message}")
        }

        // 5. 캐릭터 문서 갱신 (스토리 오버라이드 폴더에 저장)
        val updatedCharacters = updateCharacterDocuments(storyPath, dataPaths.scenarioDir(scenario.name), summary, fromTurn, toTurn)

        // 6. 대화 아카이브
        chatFileService.archiveChat(storyPath, fromTurn, toTurn, chatContent)

        // 7. chat_latest.md 초기화
        chatFileService.resetChatLatest(storyPath)

        log.info("요약 완료: storyId=$storyId, ${fromTurn}~${toTurn}턴, 갱신된 캐릭터: $updatedCharacters")

        return SummarizeResult(
            summaryFile = summaryFileName,
            turnRange = "${fromTurn}~${toTurn}",
            summary = summary,
            charactersUpdated = updatedCharacters
        )
    }

    fun shouldSummarize(turnCount: Int): Boolean {
        return turnCount > 0 && turnCount % TURNS_PER_SUMMARY == 0
    }

    // --- internal ---

    fun requestSummary(chatContent: String): String {
        val request = AiRequest(
            systemPrompt = SUMMARY_PROMPT,
            messages = listOf(ChatMessage(MessageRole.USER, chatContent)),
            purpose = AiPurpose.RECORD,
            maxTokens = 1024
        )
        return aiGateway.chat(request)
    }

    private fun updateCharacterDocuments(
        storyPath: Path,
        scenarioDir: Path,
        summary: String,
        fromTurn: Int,
        toTurn: Int
    ): List<String> {
        val baseCharDir = scenarioDir.resolve("characters")
        if (!Files.exists(baseCharDir)) return emptyList()

        val storyCharDir = storyPath.resolve("characters")
        Files.createDirectories(storyCharDir)

        val updatedNames = mutableListOf<String>()

        Files.list(baseCharDir)
            .filter { it.toString().endsWith(".md") }
            .forEach { baseCharFile ->
                val charName = baseCharFile.fileName.toString().removeSuffix(".md")
                try {
                    // 스토리 오버라이드 우선, 없으면 기본 캐릭터
                    val storyCharFile = storyCharDir.resolve("$charName.md")
                    val existingDoc = if (Files.exists(storyCharFile)) {
                        Files.readString(storyCharFile)
                    } else {
                        Files.readString(baseCharFile)
                    }
                    val updatedDoc = requestCharacterUpdate(existingDoc, summary, fromTurn, toTurn)
                    // 스토리 오버라이드 폴더에 저장 (Copy-on-Write)
                    Files.writeString(storyCharFile, updatedDoc)
                    updatedNames.add(charName)
                } catch (e: Exception) {
                    log.warn("캐릭터 문서 갱신 실패: $charName", e)
                }
            }

        return updatedNames
    }

    private fun requestCharacterUpdate(
        existingDoc: String,
        summary: String,
        fromTurn: Int,
        toTurn: Int
    ): String {
        val request = AiRequest(
            systemPrompt = CHARACTER_UPDATE_PROMPT,
            messages = listOf(
                ChatMessage(
                    MessageRole.USER,
                    """
                    ## 최근 대화 요약 (${fromTurn}~${toTurn}턴)
                    $summary

                    ## 기존 캐릭터 문서
                    $existingDoc
                    """.trimIndent()
                )
            ),
            purpose = AiPurpose.RECORD,
            maxTokens = 2048
        )
        return aiGateway.chat(request)
    }

    private fun storyDir(storyId: Long): Path {
        val story = storyRepository.findById(storyId)
            .orElseThrow { NotFoundException("스토리를 찾을 수 없습니다: $storyId") }
        val scenario = scenarioRepository.findById(story.scenarioId)
            .orElseThrow { NotFoundException("시나리오를 찾을 수 없습니다.") }
        return dataPaths.storyDir(scenario.name, story.dirName)
    }

    private fun mustRememberPath(storyId: Long): Path =
        storyDir(storyId).resolve("memory/must_remember.md")

    private fun parseSummaryRange(fileName: String): Pair<Int, Int> {
        val regex = Regex("""summary_(\d+)_(\d+)\.md""")
        val match = regex.find(fileName)
        return if (match != null) {
            match.groupValues[1].toInt() to match.groupValues[2].toInt()
        } else {
            0 to 0
        }
    }
}
