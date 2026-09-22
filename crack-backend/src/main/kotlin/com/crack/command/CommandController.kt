package com.crack.command

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/** `POST /commands/system` body */
data class SystemCommandRequest(val name: String? = null, val args: String? = null)

/** `/` 명령 API (DESIGN.md §8.2). 사용자 정의 명령의 실행은 `POST /messages`의 `command`로 한다. */
@RestController
@RequestMapping("/api/stories/{storyId}/commands")
class CommandController(
    private val commandService: CommandService,
) {

    /** 자동완성용 목록: 시스템 명령 → 사용자 정의 명령 */
    @GetMapping
    fun list(@PathVariable storyId: Long): List<CommandView> = commandService.list(storyId)

    @PostMapping("/system")
    fun runSystem(@PathVariable storyId: Long, @RequestBody request: SystemCommandRequest): SystemCommandResponse =
        commandService.runSystem(storyId, request.name, request.args)
}
