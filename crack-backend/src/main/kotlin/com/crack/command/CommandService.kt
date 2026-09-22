package com.crack.command

import com.crack.directive.Directive
import com.crack.directive.DirectiveService
import com.crack.global.exception.BadRequestException
import com.crack.memory.record.MemoryRecordService
import com.crack.memory.record.RecordReason
import com.crack.memory.record.TriggerResponse
import com.crack.story.files.StoryDirs
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.nio.file.Files
import java.nio.file.Path

/** 시스템 명령 (DESIGN.md §8.2). 즉시 실행하고 메시지를 남기지 않는다. */
enum class SystemCommand(val commandName: String, val description: String) {
    /** 기억 기록 실행 (T14 MANUAL) */
    RECORD("기록", "지금까지의 대화를 기억에 기록"),

    /** 지속 OOC 지시 추가. 인자 없이 쓰면 지시 패널을 연다(프론트 처리) */
    OOC("ooc", "해제할 때까지 지킬 지시 추가 (인자 없이 쓰면 지시 목록)");

    companion object {
        fun find(name: String?): SystemCommand? {
            val normalized = CommandService.normalize(name) ?: return null
            return entries.firstOrNull { it.commandName.equals(normalized, ignoreCase = true) }
        }
    }
}

enum class CommandType { SYSTEM, CUSTOM }

/** `GET /commands` 항목. [name]에는 `/`를 붙이지 않는다. */
data class CommandView(val name: String, val description: String, val type: CommandType)

/** `POST /commands/system` 응답. 해당하지 않는 필드는 null. */
data class SystemCommandResponse(
    val name: String,
    /** `기록`: `POST /memory/record`의 응답과 같다 */
    val record: TriggerResponse? = null,
    /** `ooc`: 추가된 지시 */
    val directive: Directive? = null,
)

/**
 * `/` 명령 (DESIGN.md §8.2, D11).
 *
 * - 시스템 명령(`기록`, `ooc`)은 [runSystem]으로 즉시 실행한다.
 * - 사용자 정의 명령은 스토리 폴더의 `commands.md`에서 읽는다(D12). 실행은 채팅 흐름이 맡고,
 *   여기서는 이번 턴 지시([turnInstruction])만 만든다.
 */
@Service
class CommandService(
    private val storyDirs: StoryDirs,
    private val directiveService: DirectiveService,
    private val memoryRecordService: MemoryRecordService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** 시스템 명령이 먼저, 사용자 정의 명령은 파일 순서. */
    fun list(storyId: Long): List<CommandView> =
        SystemCommand.entries.map { CommandView(it.commandName, it.description, CommandType.SYSTEM) } +
            customCommands(storyDirs.locate(storyId).dir).map { CommandView(it.name, it.description, CommandType.CUSTOM) }

    fun runSystem(storyId: Long, name: String?, args: String?): SystemCommandResponse {
        val command = SystemCommand.find(name) ?: throw BadRequestException("알 수 없는 시스템 명령입니다: ${name.orEmpty()}")
        return when (command) {
            SystemCommand.RECORD ->
                SystemCommandResponse(command.commandName, record = memoryRecordService.trigger(storyId, RecordReason.MANUAL))
            SystemCommand.OOC -> {
                val text = args?.trim()?.takeIf { it.isNotEmpty() }
                    ?: throw BadRequestException("추가할 지시 내용이 비어 있습니다")
                SystemCommandResponse(command.commandName, directive = directiveService.add(storyId, text))
            }
        }
    }

    /**
     * `POST /messages`의 `command`를 사용자 정의 명령으로 찾는다. 모르는 명령이나 시스템 명령이면 400.
     */
    fun requireCustom(storyId: Long, name: String?): CustomCommand {
        val normalized = normalize(name) ?: throw BadRequestException("명령 이름이 비어 있습니다")
        if (SystemCommand.find(normalized) != null) {
            throw BadRequestException("시스템 명령은 메시지로 보낼 수 없습니다: /$normalized")
        }
        return findCustom(storyDirs.locate(storyId).dir, normalized)
            ?: throw BadRequestException("알 수 없는 명령입니다: /$normalized")
    }

    /** [storyDir]의 사용자 정의 명령 중 [name]. 없으면 null. */
    fun findCustom(storyDir: Path, name: String): CustomCommand? =
        customCommands(storyDir).firstOrNull { it.name.equals(name, ignoreCase = true) }

    /**
     * 저장된 COMMAND 유저 메시지의 [content](`/일기 오늘은…`)에서 명령을 다시 찾아 이번 턴 지시를 만든다(재생성용).
     * 명령을 알아낼 수 없거나 그사이 없어졌으면 null.
     */
    fun instructionForStored(storyId: Long, content: String): String? {
        val name = nameIn(content) ?: return null
        if (SystemCommand.find(name) != null) return null
        val command = findCustom(storyDirs.locate(storyId).dir, name) ?: return null
        return turnInstruction(command, content)
    }

    fun customCommands(storyDir: Path): List<CustomCommand> {
        val file = storyDir.resolve(FILE_NAME)
        if (!Files.isRegularFile(file)) return emptyList()
        return try {
            CommandsParser.parse(Files.readString(file), SystemCommand.entries.map { it.commandName })
        } catch (e: Exception) {
            log.warn("명령 파일을 읽지 못했습니다: {} ({})", file, e.message)
            emptyList()
        }
    }

    companion object {
        const val FILE_NAME = "commands.md"

        /** 앞뒤 공백과 앞의 `/`를 뗀다. 비면 null. */
        fun normalize(name: String?): String? = name?.trim()?.removePrefix("/")?.trim()?.takeIf { it.isNotEmpty() }

        /** `/이름 …`으로 시작하는 내용에서 이름을 꺼낸다. `/`로 시작하지 않으면 null. */
        fun nameIn(content: String): String? {
            val trimmed = content.trimStart()
            if (!trimmed.startsWith("/")) return null
            return trimmed.substring(1).split(Regex("\\s+"), limit = 2).first().takeIf { it.isNotEmpty() }
        }

        /** [content]에서 앞의 `/이름`을 뗀 나머지. `/이름`으로 시작하지 않으면 내용 전체. */
        fun argsOf(command: CustomCommand, content: String): String {
            val trimmed = content.trim()
            val prefix = "/" + command.name
            if (!trimmed.startsWith(prefix, ignoreCase = true)) return trimmed
            val rest = trimmed.substring(prefix.length)
            // `/일기장`처럼 이름이 더 긴 경우는 인자가 아니다
            if (rest.isNotEmpty() && !rest.first().isWhitespace()) return trimmed
            return rest.trim()
        }

        /**
         * 이번 턴 지시 (DESIGN.md §8.2).
         * ```
         * [/일기 명령] 지금까지의 일을 주인공 시점의 일기로 써라.
         * 요청: 오늘은…
         * ```
         */
        fun turnInstruction(command: CustomCommand, content: String): String {
            val args = argsOf(command, content)
            val head = "[/${command.name} 명령] ${command.prompt}"
            return if (args.isEmpty()) head else "$head\n요청: $args"
        }
    }
}
