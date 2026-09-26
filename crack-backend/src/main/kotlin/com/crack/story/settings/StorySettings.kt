package com.crack.story.settings

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.KotlinFeature
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.fasterxml.jackson.module.kotlin.readValue
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path

/**
 * 한 응답의 목표 글자 수 (DESIGN.md §6.4 / D33).
 *
 * 하한만 주면 응답이 턴마다 길어져 지연도 같이 늘어난다(실측 720자 → 2,032자, 17.5초 → 35.5초).
 * 그래서 목표 구간과 [hardMax]("이 값을 넘기지 마라")를 함께 프롬프트에 넣는다.
 */
data class ResponseChars(
    val min: Int = DEFAULT_MIN,
    val max: Int = DEFAULT_MAX,
) {
    /** 절대 상한. 목표 상한에 [HARD_MAX_MARGIN]을 더한 값이다. 따로 설정하지 않는다. */
    val hardMax: Int get() = max + HARD_MAX_MARGIN

    /** 프롬프트에 넣어도 되는 값인지. 아니면 부르는 쪽이 기본값으로 돌아간다. */
    fun isValid(): Boolean = min > 0 && max >= min && max <= MAX_ALLOWED

    /** 잘못된 이유. [isValid]가 참이면 null. 경고 로그에 쓴다. */
    fun invalidReason(): String? = when {
        min <= 0 -> "min이 0 이하다(min=$min)"
        max < min -> "min이 max보다 크다(min=$min, max=$max)"
        max > MAX_ALLOWED -> "max가 상한 $MAX_ALLOWED 를 넘는다(max=$max)"
        else -> null
    }

    companion object {
        const val DEFAULT_MIN = 800
        const val DEFAULT_MAX = 1500

        /** `max`에 더해 절대 상한을 만드는 여유분. 기본값에서는 1,500 + 500 = 2,000자다. */
        const val HARD_MAX_MARGIN = 500

        /** 사람이 실수로 큰 값을 넣었을 때를 걸러 내는 상한. */
        const val MAX_ALLOWED = 20_000
    }
}

/**
 * 스토리 폴더의 `settings.json` (DESIGN.md §6.4).
 *
 * ```json
 * { "responseChars": { "min": 1200, "max": 2200 } }
 * ```
 *
 * 선택 파일이다. 없으면 전역 기본값(`crack.prompt.response-chars`)을 쓴다.
 * 시나리오 원본에 있으면 스토리를 만들 때 복사한다([com.crack.story.files.StoryFiles.COPIED_FILES]).
 *
 * 순수 파일 라이브러리다. 매 턴 [com.crack.prompt.contributor.BaseContributor]가 파일 하나를 읽는다.
 * **어떤 이유로든 턴을 실패시키지 않는다.** 읽기·파싱 실패와 이상한 값은 경고 로그를 남기고 기본값으로 돌아간다.
 */
data class StorySettings(
    val responseChars: ResponseChars? = null,
) {
    companion object {
        const val FILE_NAME = "settings.json"

        val EMPTY = StorySettings()

        private val log = LoggerFactory.getLogger(StorySettings::class.java)

        private val MAPPER: ObjectMapper = ObjectMapper()
            .registerModule(KotlinModule.Builder().enable(KotlinFeature.NullIsSameAsDefault).build())
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

        /** JSON 문자열. 비어 있거나 읽을 수 없으면 [EMPTY]. */
        fun fromJson(json: String, where: String = FILE_NAME): StorySettings {
            if (json.isBlank()) return EMPTY
            return try {
                MAPPER.readValue<StorySettings>(json)
            } catch (e: Exception) {
                log.warn("{}를 읽지 못해 기본값을 쓴다: {}", where, e.message)
                EMPTY
            }
        }

        /** 스토리 폴더의 `settings.json`. 파일이 없으면 [EMPTY]. */
        fun read(storyDir: Path): StorySettings {
            val file = storyDir.resolve(FILE_NAME)
            if (!Files.isRegularFile(file)) return EMPTY
            val json = try {
                Files.readString(file)
            } catch (e: Exception) {
                log.warn("{}를 읽지 못해 기본값을 쓴다: {}", file, e.message)
                return EMPTY
            }
            return fromJson(json, file.toString())
        }

        /**
         * 이 스토리에 쓸 응답 분량. 스토리 폴더의 값이 있으면 그것을, 없거나 이상하면 [fallback]을 쓴다.
         * [fallback]은 전역 설정(`crack.prompt.response-chars`)이다.
         */
        fun responseChars(storyDir: Path, fallback: ResponseChars): ResponseChars {
            val chars = read(storyDir).responseChars ?: return fallback
            val reason = chars.invalidReason()
            if (reason != null) {
                log.warn(
                    "{}의 responseChars 값이 이상해 전역 기본값을 쓴다: {} ({})",
                    storyDir.resolve(FILE_NAME), reason, fallback,
                )
                return fallback
            }
            return chars
        }
    }
}
