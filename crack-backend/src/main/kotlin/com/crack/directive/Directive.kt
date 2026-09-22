package com.crack.directive

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
import java.util.UUID

/**
 * 지속 OOC 지시 하나 (DESIGN.md §8.1, D10). `directives.json`의 항목이자 API 응답 형식이다.
 *
 * @property createdAt ISO-8601 (오프셋 포함, 초 단위)
 */
data class Directive(
    val id: String,
    val text: String,
    val enabled: Boolean = true,
    val createdAt: String,
) {
    companion object {
        fun create(text: String, enabled: Boolean = true, now: OffsetDateTime = OffsetDateTime.now()) = Directive(
            id = UUID.randomUUID().toString(),
            text = text,
            enabled = enabled,
            createdAt = now.truncatedTo(ChronoUnit.SECONDS).toString(),
        )
    }
}

/** `directives.json`이 JSON 배열로 읽히지 않을 때. 쓰기는 이 예외로 거부해 사용자의 파일을 덮어쓰지 않는다. */
class DirectiveFileException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/** 스토리 폴더의 `directives.json` 읽기·쓰기. 쓰기는 [AtomicFiles]로 한다. */
object DirectiveFile {
    const val FILE_NAME = "directives.json"

    private val MAPPER: ObjectMapper = ObjectMapper()
        .registerModule(KotlinModule.Builder().build())
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        .enable(SerializationFeature.INDENT_OUTPUT)

    fun path(storyDir: Path): Path = storyDir.resolve(FILE_NAME)

    /** 파일이 없거나 비어 있으면 빈 목록. 형식이 틀리면 [DirectiveFileException]. */
    fun read(storyDir: Path): List<Directive> {
        val file = path(storyDir)
        if (!Files.isRegularFile(file)) return emptyList()
        val json = Files.readString(file)
        if (json.isBlank()) return emptyList()
        return try {
            MAPPER.readValue<List<Directive>>(json)
        } catch (e: Exception) {
            throw DirectiveFileException("지시 파일을 읽을 수 없습니다: $FILE_NAME (${e.message?.lineSequence()?.firstOrNull()})", e)
        }
    }

    fun write(storyDir: Path, directives: List<Directive>) =
        AtomicFiles.writeString(path(storyDir), MAPPER.writeValueAsString(directives) + "\n")
}
