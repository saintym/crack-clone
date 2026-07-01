package com.crack.document.controller

import com.crack.document.dto.CharacterCreateRequest
import com.crack.document.dto.DocumentResponse
import com.crack.document.dto.DocumentUpdateRequest
import com.crack.document.service.DocumentService
import com.crack.document.service.DocumentService.DocumentType
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/scenarios/{scenarioName}")
class DocumentController(
    private val documentService: DocumentService
) {
    @GetMapping("/documents")
    fun listDocuments(@PathVariable scenarioName: String): List<DocumentResponse> {
        return documentService.listDocuments(scenarioName)
    }

    @GetMapping("/documents/{type}")
    fun readDocument(
        @PathVariable scenarioName: String,
        @PathVariable type: String
    ): DocumentResponse {
        return documentService.readDocument(scenarioName, parseDocumentType(type))
    }

    @PutMapping("/documents/{type}")
    fun updateDocument(
        @PathVariable scenarioName: String,
        @PathVariable type: String,
        @RequestBody request: DocumentUpdateRequest
    ): DocumentResponse {
        return documentService.updateDocument(scenarioName, parseDocumentType(type), request.content)
    }

    @GetMapping("/characters")
    fun listCharacters(@PathVariable scenarioName: String): List<DocumentResponse> {
        return documentService.listCharacters(scenarioName)
    }

    @GetMapping("/characters/{charName}")
    fun readCharacter(
        @PathVariable scenarioName: String,
        @PathVariable charName: String
    ): DocumentResponse {
        return documentService.readCharacter(scenarioName, charName)
    }

    @PostMapping("/characters")
    @ResponseStatus(HttpStatus.CREATED)
    fun createCharacter(
        @PathVariable scenarioName: String,
        @RequestBody request: CharacterCreateRequest
    ): DocumentResponse {
        return documentService.createCharacter(scenarioName, request.name, request.content)
    }

    @PutMapping("/characters/{charName}")
    fun updateCharacter(
        @PathVariable scenarioName: String,
        @PathVariable charName: String,
        @RequestBody request: DocumentUpdateRequest
    ): DocumentResponse {
        return documentService.updateCharacter(scenarioName, charName, request.content)
    }

    @DeleteMapping("/characters/{charName}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun deleteCharacter(
        @PathVariable scenarioName: String,
        @PathVariable charName: String
    ) {
        documentService.deleteCharacter(scenarioName, charName)
    }

    private fun parseDocumentType(type: String): DocumentType {
        return try {
            DocumentType.valueOf(type.uppercase())
        } catch (e: IllegalArgumentException) {
            throw com.crack.global.exception.BadRequestException(
                "유효하지 않은 문서 타입: $type (가능: ${DocumentType.entries.joinToString { it.name.lowercase() }})"
            )
        }
    }
}
