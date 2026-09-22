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
 * `{"companions": ["설월"], "location": "흑풍채 근처 숲", "time": "3일차 밤", "updatedAtTurn": 30}`
 *
 * 모르는 필드는 무시하고, 빠진 필드는 기본값을 쓴다.
 */
data class StoryState(
    val companions: List<String> = emptyList(),
    val location: String? = null,
    val time: String? = null,
    val updatedAtTurn: Int? = null,
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
            return state.copy(companions = (state.companions as List<String?>).filterNotNull())
        }

        /** 파일을 읽는다. 파일이 없으면 빈 기본값. */
        fun read(path: Path): StoryState =
            if (Files.isRegularFile(path)) fromJson(Files.readString(path)) else EMPTY
    }
}
