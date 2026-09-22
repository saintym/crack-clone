package com.crack.setting

import com.crack.global.exception.NotFoundException
import com.crack.scenario.entity.Scenario
import com.crack.scenario.repository.ScenarioRepository
import com.crack.setting.dto.*
import com.crack.setting.entity.ScenarioSetting
import com.crack.setting.entity.SettingKey
import com.crack.setting.repository.ScenarioSettingRepository
import com.crack.setting.service.SettingService
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.*

@ExtendWith(MockitoExtension::class)
class SettingServiceTest {

    private lateinit var scenarioRepository: ScenarioRepository
    private lateinit var scenarioSettingRepository: ScenarioSettingRepository
    private lateinit var settingService: SettingService

    private val testScenario = Scenario(
        id = 1L, name = "test-scenario", title = "Test"
    )

    @BeforeEach
    fun setUp() {
        scenarioRepository = mock()
        scenarioSettingRepository = mock()
        settingService = SettingService(scenarioRepository, scenarioSettingRepository)
    }

    @Test
    fun `새 설정을 생성하면 DB에 저장된다`() {
        // given
        whenever(scenarioRepository.findByName("test-scenario")).thenReturn(testScenario)
        whenever(scenarioSettingRepository.findByScenarioIdAndSettingKey(1L, SettingKey.OUTPUT_TEMPLATE))
            .thenReturn(null)
        whenever(scenarioSettingRepository.save(any<ScenarioSetting>())).thenAnswer { invocation ->
            val setting = invocation.getArgument<ScenarioSetting>(0)
            ScenarioSetting(
                id = 1L,
                scenarioId = setting.scenarioId,
                settingKey = setting.settingKey,
                settingValue = setting.settingValue
            )
        }

        // when
        val result = settingService.createOrUpdate("test-scenario", SettingCreateRequest(
            settingKey = SettingKey.OUTPUT_TEMPLATE,
            settingValue = "## 감정\n[감정: {{감정}}]\n\n## 행동\n*{{행동}}*"
        ))

        // then
        assertEquals(SettingKey.OUTPUT_TEMPLATE, result.settingKey)
        assertTrue(result.settingValue.contains("감정"))
        verify(scenarioSettingRepository).save(any<ScenarioSetting>())
    }

    @Test
    fun `동일 키로 생성 시 기존 설정이 업데이트된다`() {
        // given
        val existing = ScenarioSetting(
            id = 1L, scenarioId = 1L,
            settingKey = SettingKey.VOLUME_RULE,
            settingValue = "500자 이내"
        )
        whenever(scenarioRepository.findByName("test-scenario")).thenReturn(testScenario)
        whenever(scenarioSettingRepository.findByScenarioIdAndSettingKey(1L, SettingKey.VOLUME_RULE))
            .thenReturn(existing)

        // when
        val result = settingService.createOrUpdate("test-scenario", SettingCreateRequest(
            settingKey = SettingKey.VOLUME_RULE,
            settingValue = "1000자 이내"
        ))

        // then
        assertEquals("1000자 이내", result.settingValue)
        verify(scenarioSettingRepository, never()).save(any<ScenarioSetting>())
    }

    @Test
    fun `설정 키로 업데이트가 동작한다`() {
        // given
        val existing = ScenarioSetting(
            id = 1L, scenarioId = 1L,
            settingKey = SettingKey.DIALOGUE_FORMAT,
            settingValue = "따옴표 사용"
        )
        whenever(scenarioRepository.findByName("test-scenario")).thenReturn(testScenario)
        whenever(scenarioSettingRepository.findByScenarioIdAndSettingKey(1L, SettingKey.DIALOGUE_FORMAT))
            .thenReturn(existing)

        // when
        val result = settingService.update("test-scenario", SettingKey.DIALOGUE_FORMAT,
            SettingUpdateRequest(settingValue = "쌍따옴표 사용")
        )

        // then
        assertEquals("쌍따옴표 사용", result.settingValue)
    }

    @Test
    fun `존재하지 않는 설정 업데이트 시 예외 발생`() {
        // given
        whenever(scenarioRepository.findByName("test-scenario")).thenReturn(testScenario)
        whenever(scenarioSettingRepository.findByScenarioIdAndSettingKey(1L, SettingKey.CUSTOM_RULE))
            .thenReturn(null)

        // when & then
        assertThrows<NotFoundException> {
            settingService.update("test-scenario", SettingKey.CUSTOM_RULE,
                SettingUpdateRequest(settingValue = "규칙")
            )
        }
    }

    @Test
    fun `시나리오의 전체 설정을 조회할 수 있다`() {
        // given
        val settings = listOf(
            ScenarioSetting(id = 1L, scenarioId = 1L,
                settingKey = SettingKey.OUTPUT_TEMPLATE, settingValue = "템플릿"),
            ScenarioSetting(id = 2L, scenarioId = 1L,
                settingKey = SettingKey.VOLUME_RULE, settingValue = "500자"),
            ScenarioSetting(id = 3L, scenarioId = 1L,
                settingKey = SettingKey.DIALOGUE_FORMAT, settingValue = "따옴표")
        )
        whenever(scenarioRepository.findByName("test-scenario")).thenReturn(testScenario)
        whenever(scenarioSettingRepository.findByScenarioId(1L)).thenReturn(settings)

        // when
        val result = settingService.getAll("test-scenario")

        // then
        assertEquals(3, result.size)
        assertEquals(SettingKey.OUTPUT_TEMPLATE, result[0].settingKey)
        assertEquals(SettingKey.VOLUME_RULE, result[1].settingKey)
    }

    @Test
    fun `특정 설정 키로 조회할 수 있다`() {
        // given
        val setting = ScenarioSetting(
            id = 1L, scenarioId = 1L,
            settingKey = SettingKey.EXAMPLE_SCENE,
            settingValue = "예시 장면 내용..."
        )
        whenever(scenarioRepository.findByName("test-scenario")).thenReturn(testScenario)
        whenever(scenarioSettingRepository.findByScenarioIdAndSettingKey(1L, SettingKey.EXAMPLE_SCENE))
            .thenReturn(setting)

        // when
        val result = settingService.getByKey("test-scenario", SettingKey.EXAMPLE_SCENE)

        // then
        assertEquals(SettingKey.EXAMPLE_SCENE, result.settingKey)
        assertEquals("예시 장면 내용...", result.settingValue)
    }

    @Test
    fun `존재하지 않는 설정 키 조회 시 예외 발생`() {
        // given
        whenever(scenarioRepository.findByName("test-scenario")).thenReturn(testScenario)
        whenever(scenarioSettingRepository.findByScenarioIdAndSettingKey(1L, SettingKey.CUSTOM_RULE))
            .thenReturn(null)

        // when & then
        assertThrows<NotFoundException> {
            settingService.getByKey("test-scenario", SettingKey.CUSTOM_RULE)
        }
    }

    @Test
    fun `설정을 삭제할 수 있다`() {
        // given
        val setting = ScenarioSetting(
            id = 1L, scenarioId = 1L,
            settingKey = SettingKey.CUSTOM_RULE,
            settingValue = "커스텀 규칙"
        )
        whenever(scenarioRepository.findByName("test-scenario")).thenReturn(testScenario)
        whenever(scenarioSettingRepository.findByScenarioIdAndSettingKey(1L, SettingKey.CUSTOM_RULE))
            .thenReturn(setting)

        // when
        settingService.delete("test-scenario", SettingKey.CUSTOM_RULE)

        // then
        verify(scenarioSettingRepository).delete(setting)
    }

    @Test
    fun `존재하지 않는 시나리오로 설정 조회 시 예외 발생`() {
        // given
        whenever(scenarioRepository.findByName("없음")).thenReturn(null)

        // when & then
        assertThrows<NotFoundException> {
            settingService.getAll("없음")
        }
    }

    @Test
    fun `기본값이 있는 설정 조회 시 설정이 없으면 기본값을 반환한다`() {
        // given
        whenever(scenarioSettingRepository.findByScenarioIdAndSettingKey(1L, SettingKey.VOLUME_RULE))
            .thenReturn(null)

        // when
        val result = settingService.getSettingValueOrDefault(1L, SettingKey.VOLUME_RULE, "300자")

        // then
        assertEquals("300자", result)
    }

    @Test
    fun `기본값이 있는 설정 조회 시 설정이 있으면 설정값을 반환한다`() {
        // given
        val setting = ScenarioSetting(
            id = 1L, scenarioId = 1L,
            settingKey = SettingKey.VOLUME_RULE,
            settingValue = "1000자"
        )
        whenever(scenarioSettingRepository.findByScenarioIdAndSettingKey(1L, SettingKey.VOLUME_RULE))
            .thenReturn(setting)

        // when
        val result = settingService.getSettingValueOrDefault(1L, SettingKey.VOLUME_RULE, "300자")

        // then
        assertEquals("1000자", result)
    }
}
