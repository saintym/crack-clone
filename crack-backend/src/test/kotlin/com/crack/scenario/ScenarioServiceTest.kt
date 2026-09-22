package com.crack.scenario

import com.crack.global.config.DataPathConfig
import com.crack.global.config.DataPaths
import com.crack.global.exception.BadRequestException
import com.crack.global.exception.NotFoundException
import com.crack.scenario.dto.ScenarioCreateRequest
import com.crack.scenario.entity.Scenario
import com.crack.scenario.entity.ScenarioStatus
import com.crack.scenario.repository.ScenarioRepository
import com.crack.scenario.service.ScenarioService
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.kotlin.*
import org.mockito.junit.jupiter.MockitoExtension
import java.nio.file.Files
import java.nio.file.Path

@ExtendWith(MockitoExtension::class)
class ScenarioServiceTest {

    private lateinit var scenarioRepository: ScenarioRepository
    private lateinit var dataPathConfig: DataPathConfig
    private lateinit var scenarioService: ScenarioService
    private lateinit var tempDir: Path

    @BeforeEach
    fun setUp() {
        tempDir = Files.createTempDirectory("crack-test")
        // 템플릿 디렉토리 및 파일 생성
        val templateDir = tempDir.resolve("_templates")
        Files.createDirectories(templateDir)
        Files.writeString(templateDir.resolve("world.md"), "# 세계관\n\n## 시대\n")
        Files.writeString(templateDir.resolve("scenario.md"), "# 시나리오\n\n## 초기 상황\n")
        Files.writeString(templateDir.resolve("protagonist.md"), "# 주인공\n")
        Files.writeString(templateDir.resolve("must_remember.md"), "# 필수 기억사항\n")

        scenarioRepository = mock()
        dataPathConfig = DataPathConfig(dataPath = tempDir.toString())
        scenarioService = ScenarioService(scenarioRepository, DataPaths(dataPathConfig))
    }

    @AfterEach
    fun tearDown() {
        Files.walk(tempDir)
            .sorted(Comparator.reverseOrder())
            .forEach { Files.deleteIfExists(it) }
    }

    @Test
    fun `시나리오 생성 시 폴더 구조가 올바르게 생성된다`() {
        // given
        val request = ScenarioCreateRequest(name = "테스트시나리오", title = "테스트 시나리오")
        whenever(scenarioRepository.existsByName("테스트시나리오")).thenReturn(false)
        whenever(scenarioRepository.save(any<Scenario>())).thenAnswer { invocation ->
            val scenario = invocation.getArgument<Scenario>(0)
            Scenario(
                id = 1L,
                name = scenario.name,
                title = scenario.title
            )
        }

        // when
        val result = scenarioService.create(request)

        // then
        assertEquals("테스트시나리오", result.name)
        assertEquals("테스트 시나리오", result.title)

        val scenarioDir = tempDir.resolve("테스트시나리오")
        assertTrue(Files.exists(scenarioDir), "시나리오 폴더가 생성되어야 한다")
        assertTrue(Files.exists(scenarioDir.resolve("world.md")), "world.md가 있어야 한다")
        assertTrue(Files.exists(scenarioDir.resolve("scenario.md")), "scenario.md가 있어야 한다")
        assertTrue(Files.exists(scenarioDir.resolve("characters/protagonist.md")), "protagonist.md가 있어야 한다")
        assertTrue(Files.exists(scenarioDir.resolve("memory/must_remember.md")), "must_remember.md가 있어야 한다")
        assertTrue(Files.exists(scenarioDir.resolve("chat/chat_latest.md")), "chat_latest.md가 있어야 한다")
        assertTrue(Files.exists(scenarioDir.resolve("chat/archive")), "chat/archive 폴더가 있어야 한다")
    }

    @Test
    fun `시나리오 생성 시 템플릿 내용이 복사된다`() {
        // given
        val request = ScenarioCreateRequest(name = "복사테스트", title = "복사 테스트")
        whenever(scenarioRepository.existsByName("복사테스트")).thenReturn(false)
        whenever(scenarioRepository.save(any<Scenario>())).thenAnswer { invocation ->
            invocation.getArgument<Scenario>(0)
        }

        // when
        scenarioService.create(request)

        // then
        val worldContent = Files.readString(tempDir.resolve("복사테스트/world.md"))
        assertTrue(worldContent.contains("# 세계관"), "world.md에 템플릿 내용이 있어야 한다")
    }

    @Test
    fun `중복된 시나리오 이름으로 생성하면 예외가 발생한다`() {
        // given
        whenever(scenarioRepository.existsByName("중복")).thenReturn(true)

        // when & then
        assertThrows<BadRequestException> {
            scenarioService.create(ScenarioCreateRequest(name = "중복", title = "중복"))
        }
    }

    @Test
    fun `시나리오 삭제 시 폴더도 함께 삭제된다`() {
        // given
        val scenarioDir = tempDir.resolve("삭제대상")
        Files.createDirectories(scenarioDir.resolve("characters"))
        Files.writeString(scenarioDir.resolve("world.md"), "# 세계관")

        val scenario = Scenario(
            id = 1L, name = "삭제대상", title = "삭제 테스트"
        )
        whenever(scenarioRepository.findByName("삭제대상")).thenReturn(scenario)

        // when
        scenarioService.delete("삭제대상")

        // then
        assertFalse(Files.exists(scenarioDir), "시나리오 폴더가 삭제되어야 한다")
        verify(scenarioRepository).delete(scenario)
    }

    @Test
    fun `존재하지 않는 시나리오 조회 시 예외가 발생한다`() {
        // given
        whenever(scenarioRepository.findByName("없음")).thenReturn(null)

        // when & then
        assertThrows<NotFoundException> {
            scenarioService.findByName("없음")
        }
    }

    @Test
    fun `ACTIVE 상태 시나리오만 목록에 반환된다`() {
        // given
        val scenarios = listOf(
            Scenario(id = 1L, name = "s1", title = "S1"),
            Scenario(id = 2L, name = "s2", title = "S2")
        )
        whenever(scenarioRepository.findByStatus(ScenarioStatus.ACTIVE)).thenReturn(scenarios)

        // when
        val result = scenarioService.findAll()

        // then
        assertEquals(2, result.size)
        assertEquals("s1", result[0].name)
        assertEquals("s2", result[1].name)
    }
}
