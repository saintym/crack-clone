package com.crack.image

import com.crack.document.service.DocumentService
import com.crack.document.service.DocumentService.DocumentType
import com.crack.global.exception.NotFoundException
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * 일괄 등록 요청. [apply]가 false면 미리보기만 하고 파일을 건드리지 않는다.
 *
 * 입구가 둘이다(T42).
 * - **칸을 따로**(권장): [urlTemplate], [characters], [actions]. 화면이 세 칸으로 나뉘어 있을 때
 * - **한 덩이**: [spec] 하나에 제목과 표를 같이 넣는다. [urlTemplate]이 비어 있을 때만 쓴다
 */
data class ImageBulkRequest(
    val spec: String = "",
    val urlTemplate: String = "",
    val characters: String = "",
    val actions: String = "",
    val apply: Boolean = false,
)

/**
 * @property added 실제로 `images.md`에 더한 수 ([ImageBulkRequest.apply]가 false면 0)
 * @property duplicates 이미 같은 태그가 있어 건너뛴 수
 */
data class ImageBulkResponse(
    val urlTemplate: String,
    val characters: List<ImageBulkSpecParser.Code>,
    val actions: List<ImageBulkSpecParser.Code>,
    val entries: List<ImageEntry>,
    val warnings: List<String>,
    val added: Int,
    val duplicates: Int,
)

/**
 * 이미지 카탈로그 일괄 등록 (DESIGN.md §8.6, T41).
 *
 * `POST /api/scenarios/{name}/images/bulk` — 명세를 파싱해 미리보기를 주고, `apply`면 `images.md` **끝에 덧붙인다.**
 *
 * 주소 틀·캐릭터 목록·행동 목록을 **칸으로 따로** 받는 것이 기본이고, 한 덩이 명세도 그대로 받는다(T42).
 *
 * **덧붙이기만 한다.** 기존 줄과 주석은 건드리지 않고, 이미 있는 태그는 건너뛴다.
 * 존재하지 않는 조합(캐릭터마다 있는 행동이 다를 때)은 사용자가 그 줄만 지워서 정리한다.
 */
@RestController
@RequestMapping("/api/scenarios/{scenarioName}/images")
class ImageBulkController(private val documentService: DocumentService) {

    @PostMapping("/bulk")
    fun bulk(
        @PathVariable scenarioName: String,
        @RequestBody request: ImageBulkRequest,
    ): ImageBulkResponse {
        val parsed = if (request.urlTemplate.isNotBlank()) {
            ImageBulkSpecParser.parseFields(request.urlTemplate, request.characters, request.actions)
        } else {
            ImageBulkSpecParser.parse(request.spec)
        }
        val existing = readImages(scenarioName)
        val known = ImageCatalogParser.parse(existing).map { it.tag }.toHashSet()

        val fresh = parsed.entries.filter { known.add(it.tag) }
        val duplicates = parsed.entries.size - fresh.size

        var added = 0
        if (request.apply && fresh.isNotEmpty()) {
            documentService.updateDocument(scenarioName, DocumentType.IMAGES, append(existing, fresh))
            added = fresh.size
        }
        return ImageBulkResponse(
            urlTemplate = parsed.urlTemplate,
            characters = parsed.characters,
            actions = parsed.actions,
            entries = fresh,
            warnings = parsed.warnings,
            added = added,
            duplicates = duplicates,
        )
    }

    /** `images.md`가 없으면 빈 문서로 본다. 적용할 때 새로 만든다. */
    private fun readImages(scenarioName: String): String = try {
        documentService.readDocument(scenarioName, DocumentType.IMAGES).content
    } catch (e: NotFoundException) {
        ""
    }

    private fun append(existing: String, entries: List<ImageEntry>): String {
        val body = entries.joinToString("\n") { e ->
            if (e.description.isEmpty()) "- ${e.tag}: ${e.url}" else "- ${e.tag}: ${e.url} | ${e.description}"
        }
        val head = existing.trimEnd()
        return if (head.isEmpty()) "$body\n" else "$head\n\n$body\n"
    }
}
