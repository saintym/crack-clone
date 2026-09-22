package com.crack.scenario.service

import com.crack.global.config.DataPaths
import com.crack.global.exception.BadRequestException
import com.crack.global.exception.NotFoundException
import com.crack.scenario.dto.ScenarioCreateRequest
import com.crack.scenario.dto.ScenarioResponse
import com.crack.scenario.entity.Scenario
import com.crack.scenario.entity.ScenarioStatus
import com.crack.scenario.repository.ScenarioRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.FileAlreadyExistsException
import java.nio.file.LinkOption
import java.nio.file.StandardOpenOption

@Service
@Transactional(readOnly = true)
class ScenarioService(
    private val scenarioRepository: ScenarioRepository,
    private val dataPaths: DataPaths
) {
    private val templateDir: Path
        get() = dataPaths.templatesDir()

    @Transactional
    fun create(request: ScenarioCreateRequest): ScenarioResponse {
        if (scenarioRepository.existsByName(request.name)) {
            throw BadRequestException("시나리오 '${request.name}'이(가) 이미 존재합니다.")
        }

        val scenarioDir = dataPaths.scenarioDir(request.name)
        initScenarioDirectory(scenarioDir)

        val scenario = scenarioRepository.save(
            Scenario(
                name = request.name,
                title = request.title
            )
        )
        return ScenarioResponse.from(scenario)
    }

    fun findAll(): List<ScenarioResponse> {
        return scenarioRepository.findByStatus(ScenarioStatus.ACTIVE)
            .map { ScenarioResponse.from(it) }
    }

    fun findByName(name: String): ScenarioResponse {
        val scenario = scenarioRepository.findByName(name)
            ?: throw NotFoundException("시나리오 '$name'을(를) 찾을 수 없습니다.")
        return ScenarioResponse.from(scenario)
    }

    @Transactional
    fun delete(name: String) {
        val scenario = scenarioRepository.findByName(name)
            ?: throw NotFoundException("시나리오 '$name'을(를) 찾을 수 없습니다.")

        val scenarioDir = dataPaths.scenarioDir(scenario.name)
        if (Files.exists(scenarioDir)) {
            Files.walk(scenarioDir)
                .sorted(Comparator.reverseOrder())
                .forEach { Files.deleteIfExists(it) }
        }

        scenarioRepository.delete(scenario)
    }

    /**
     * 시나리오 폴더의 기본 구조를 만든다. **이미 있는 파일은 건드리지 않는다**(BUG-006).
     * 기존 시나리오 폴더를 DB에 등록할 때 원본 문서가 템플릿으로 덮어써지지 않게 하기 위해서다.
     */
    private fun initScenarioDirectory(scenarioDir: Path) {
        Files.createDirectories(scenarioDir)
        Files.createDirectories(scenarioDir.resolve("characters"))
        Files.createDirectories(scenarioDir.resolve("memory"))
        Files.createDirectories(scenarioDir.resolve("chat"))
        Files.createDirectories(scenarioDir.resolve("chat/archive"))

        copyTemplate("world.md", scenarioDir.resolve("world.md"))
        copyTemplate("scenario.md", scenarioDir.resolve("scenario.md"))
        copyTemplate("protagonist.md", scenarioDir.resolve("characters/protagonist.md"))
        copyTemplate("must_remember.md", scenarioDir.resolve("memory/must_remember.md"))

        writeIfAbsent(scenarioDir.resolve("chat/chat_latest.md"), "# 최근 대화\n\n")
    }

    /** 템플릿을 [target]에 복사한다. [target]이 이미 있으면 아무것도 하지 않는다. */
    private fun copyTemplate(templateName: String, target: Path) {
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) return
        val source = templateDir.resolve(templateName)
        try {
            if (Files.exists(source)) {
                Files.copy(source, target)
            } else {
                Files.writeString(target, "# $templateName\n", StandardOpenOption.CREATE_NEW)
            }
        } catch (e: FileAlreadyExistsException) {
            // 확인과 쓰기 사이에 생긴 파일도 덮어쓰지 않는다
        }
    }

    private fun writeIfAbsent(target: Path, content: String) {
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) return
        try {
            Files.writeString(target, content, StandardOpenOption.CREATE_NEW)
        } catch (e: FileAlreadyExistsException) {
            // 덮어쓰지 않는다
        }
    }
}
