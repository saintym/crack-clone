package com.crack.document.service

import com.crack.document.dto.DocumentResponse
import com.crack.global.config.DataPaths
import com.crack.global.exception.BadRequestException
import com.crack.global.exception.NotFoundException
import org.springframework.stereotype.Service
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

@Service
class DocumentService(
    private val dataPaths: DataPaths
) {
    private companion object {
        const val CHARACTERS_DIR = "characters"
    }

    /** 시나리오 원본 문서 타입 (DESIGN.md §2, §9). 선택 문서(prologue, keywords, commands, images)는 없을 수 있다. */
    enum class DocumentType(val fileName: String) {
        WORLD("world.md"),
        SCENARIO("scenario.md"),
        PROTAGONIST("characters/protagonist.md"),
        PROLOGUE("prologue.md"),
        KEYWORDS("keywords.md"),
        COMMANDS("commands.md"),
        IMAGES("images.md")
    }

    fun readDocument(scenarioName: String, type: DocumentType): DocumentResponse {
        val path = resolveDocumentPath(scenarioName, type.fileName)
        if (!Files.exists(path)) {
            throw NotFoundException("문서를 찾을 수 없습니다: ${type.fileName}")
        }
        return DocumentResponse(
            type = type.name.lowercase(),
            name = type.fileName,
            content = Files.readString(path)
        )
    }

    fun updateDocument(scenarioName: String, type: DocumentType, content: String): DocumentResponse {
        val path = resolveDocumentPath(scenarioName, type.fileName)
        ensureScenarioExists(scenarioName)
        Files.createDirectories(path.parent)
        Files.writeString(path, content)
        return DocumentResponse(
            type = type.name.lowercase(),
            name = type.fileName,
            content = content
        )
    }

    fun listDocuments(scenarioName: String): List<DocumentResponse> {
        ensureScenarioExists(scenarioName)
        return DocumentType.entries.mapNotNull { type ->
            val path = resolveDocumentPath(scenarioName, type.fileName)
            if (Files.exists(path)) {
                DocumentResponse(
                    type = type.name.lowercase(),
                    name = type.fileName,
                    content = Files.readString(path)
                )
            } else null
        }
    }

    // --- 캐릭터 관련 ---

    fun listCharacters(scenarioName: String): List<DocumentResponse> {
        val charDir = scenarioDir(scenarioName).resolve("characters")
        if (!Files.exists(charDir)) return emptyList()

        return Files.list(charDir)
            .filter { it.toString().endsWith(".md") && it.fileName.toString() != "protagonist.md" }
            .map { path ->
                DocumentResponse(
                    type = "character",
                    name = path.fileName.toString().removeSuffix(".md"),
                    content = Files.readString(path)
                )
            }
            .toList()
    }

    fun readCharacter(scenarioName: String, charName: String): DocumentResponse {
        val path = characterPath(scenarioName, charName)
        if (!Files.exists(path)) {
            throw NotFoundException("캐릭터 '$charName'을(를) 찾을 수 없습니다.")
        }
        return DocumentResponse(
            type = "character",
            name = charName,
            content = Files.readString(path)
        )
    }

    fun createCharacter(scenarioName: String, charName: String, content: String?): DocumentResponse {
        val path = characterPath(scenarioName, charName)
        if (Files.exists(path)) {
            throw BadRequestException("캐릭터 '$charName'이(가) 이미 존재합니다.")
        }

        val charContent = content ?: loadCharacterTemplate(charName)
        Files.createDirectories(path.parent)
        Files.writeString(path, charContent)

        return DocumentResponse(
            type = "character",
            name = charName,
            content = charContent
        )
    }

    fun updateCharacter(scenarioName: String, charName: String, content: String): DocumentResponse {
        val path = characterPath(scenarioName, charName)
        if (!Files.exists(path)) {
            throw NotFoundException("캐릭터 '$charName'을(를) 찾을 수 없습니다.")
        }
        Files.writeString(path, content)
        return DocumentResponse(
            type = "character",
            name = charName,
            content = content
        )
    }

    fun deleteCharacter(scenarioName: String, charName: String) {
        val path = characterPath(scenarioName, charName)
        if (!Files.exists(path)) {
            throw NotFoundException("캐릭터 '$charName'을(를) 찾을 수 없습니다.")
        }
        Files.delete(path)
    }

    // --- internal ---

    private fun scenarioDir(scenarioName: String): Path =
        dataPaths.scenarioDir(scenarioName)

    private fun resolveDocumentPath(scenarioName: String, fileName: String): Path =
        scenarioDir(scenarioName).resolve(fileName)

    /**
     * 인물 문서 경로. [charName]은 `characters/` 안의 파일 이름 하나(경로 조각 하나)만 허용한다.
     * `../world` 같은 이름으로 `characters/` 밖을 읽고 쓰지 못하게 한다(T12, `DataPaths`와 같은 규칙).
     */
    private fun characterPath(scenarioName: String, charName: String): Path =
        scenarioDir(scenarioName).resolve(CHARACTERS_DIR).resolve("${requireCharacterName(charName)}.md")

    private fun requireCharacterName(charName: String): String {
        if (charName.isBlank() || charName == "." || charName == ".." ||
            charName.contains('/') || charName.contains('\\') || charName.contains('\u0000')
        ) {
            throw BadRequestException("캐릭터 이름이 올바르지 않습니다: '$charName'")
        }
        return charName
    }

    private fun ensureScenarioExists(scenarioName: String) {
        if (!Files.exists(scenarioDir(scenarioName))) {
            throw NotFoundException("시나리오 '$scenarioName'을(를) 찾을 수 없습니다.")
        }
    }

    private fun loadCharacterTemplate(charName: String): String {
        val templatePath = dataPaths.templatesDir().resolve("character.md")
        return if (Files.exists(templatePath)) {
            Files.readString(templatePath).replace("(이름)", charName)
        } else {
            "# 캐릭터: $charName\n"
        }
    }
}
