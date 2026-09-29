package com.crack.memory.docs

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.module.kotlin.KotlinFeature
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.fasterxml.jackson.module.kotlin.readValue
import java.nio.file.Files
import java.nio.file.Path

/**
 * 스토리 폴더의 `state.json` (DESIGN.md §7.1).
 *
 * `{"companions": ["설월"], "location": "흑풍채 근처 숲", "time": "3일차 밤", "updatedAtTurn": 30,`
 * `"changedDocs": ["characters/설월.md"], "clock": "2026-09-28T23:40", "place": "에미야 저택"}`
 *
 * 모르는 필드는 무시하고, 빠진 필드는 기본값을 쓴다.
 */
data class StoryState(
    val companions: List<String> = emptyList(),
    val location: String? = null,
    val time: String? = null,
    val updatedAtTurn: Int? = null,
    /**
     * 사용자가 방금 고친 스토리 문서 경로 (T39). 다음 응답 한 번에만 신호로 쓰이고 지워진다.
     *
     * 기록 파이프라인이 쓴 것은 여기 들어가지 않는다. **사람이 고친 것만** 담는다.
     */
    val changedDocs: List<String> = emptyList(),
    /**
     * 이야기 속 현재 시각 (T38, D39). `2026-09-28T23:40` 형식의 문자열이다.
     *
     * 문자열로 두는 이유는 값 하나가 깨져도 `state.json` 읽기 전체가 실패하지 않게 하려는 것이다.
     * 해석은 [com.crack.story.clock.StoryClock]이 한다. 값이 없으면 시계를 아직 쓰지 않는다는 뜻이고,
     * 이때는 `settings.json`의 `clock.start`로 폴백한다.
     *
     * 기록 파이프라인이 쓰는 [time]("3일차 밤")과 다르다. 이쪽은 시계가 관리하는 절대 시각이다.
     */
    val clock: String? = null,
    /** 이야기 속 현재 장소 (T38). 자유 문자열이다. 기록 파이프라인이 쓰는 [location]과 별개다 */
    val place: String? = null,
) {
    fun toJson(): String = MAPPER.writeValueAsString(this)

    /** 원자적으로 파일에 쓴다. */
    fun write(path: Path) = AtomicFiles.writeString(path, toJson() + "\n")

    companion object {
        val EMPTY = StoryState()

        private val MAPPER: ObjectMapper = ObjectMapper()
            .registerModule(KotlinModule.Builder().enable(KotlinFeature.NullIsSameAsDefault).build())
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .enable(SerializationFeature.INDENT_OUTPUT)

        /** JSON 문자열을 읽는다. 비어 있으면 기본값. `null` 값은 기본값으로, `null` 배열 원소는 버린다. */
        fun fromJson(json: String): StoryState {
            if (json.isBlank()) return EMPTY
            val state: StoryState = MAPPER.readValue(json)
            // Kotlin 모듈은 List<String>의 null 원소를 막지 않는다
            @Suppress("USELESS_CAST", "SENSELESS_COMPARISON")
            return state.copy(
                companions = (state.companions as List<String?>).filterNotNull(),
                changedDocs = (state.changedDocs as List<String?>).filterNotNull(),
            )
        }

        /** 파일을 읽는다. 파일이 없으면 빈 기본값. */
        fun read(path: Path): StoryState =
            if (Files.isRegularFile(path)) fromJson(Files.readString(path)) else EMPTY
    }
}
