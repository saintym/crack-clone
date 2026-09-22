package com.crack.story.files

import com.crack.memory.docs.AtomicFiles
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.fasterxml.jackson.module.kotlin.readValue
import java.nio.file.Files
import java.nio.file.Path
import java.time.OffsetDateTime
import java.time.temporal.ChronoUnit

/**
 * 스토리 폴더의 `story.json` (DESIGN.md §2): `{ "scenarioName", "copiedAt", "formatVersion": 2 }`.
 * [StoryFiles.initFromScenario]가 마지막에 쓴다. 이 파일이 있으면 v2 형식으로 끝까지 만들어진 스토리다.
 */
data class StoryMeta(
    val scenarioName: String,
    /** ISO-8601 (오프셋 포함, 초 단위) */
    val copiedAt: String,
    val formatVersion: Int = FORMAT_VERSION,
) {
    fun toJson(): String = MAPPER.writeValueAsString(this)

    fun write(path: Path) = AtomicFiles.writeString(path, toJson() + "\n")

    companion object {
        const val FORMAT_VERSION = 2

        private val MAPPER: ObjectMapper = ObjectMapper()
            .registerModule(KotlinModule.Builder().build())
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .enable(SerializationFeature.INDENT_OUTPUT)

        fun create(scenarioName: String, now: OffsetDateTime = OffsetDateTime.now()): StoryMeta =
            StoryMeta(scenarioName = scenarioName, copiedAt = now.truncatedTo(ChronoUnit.SECONDS).toString())

        fun fromJson(json: String): StoryMeta = MAPPER.readValue(json)

        fun read(path: Path): StoryMeta = fromJson(Files.readString(path))
    }
}
