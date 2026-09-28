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
        val warnings = parsed.warnings + missingCharacterWarnings(scenarioName, parsed)
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
            warnings = warnings,
            added = added,
            duplicates = duplicates,
        )
    }

    /**
     * 이 시나리오에 **인물 문서가 없는 이름**을 경고한다 (T43).
     *
     * 태그는 `{인물 문서 파일명}_{변형}`이어야 인물 이미지로 동작한다. 이름이 안 맞으면
     * ⑴ 그 인물이 활성 인물이 될 수 없어 변형 목록이 프롬프트에 안 들어가고,
     * ⑵ **인물 이름으로 시작하지 않는 태그는 장면·배경 태그로 분류되어** AI가 배경으로 끼워 넣을 수 있다.
     *
     * 다른 작품의 코드표를 엉뚱한 시나리오에 붙이는 실수가 조용히 지나가지 않게 막는다.
     */
    private fun missingCharacterWarnings(
        scenarioName: String,
        parsed: ImageBulkSpecParser.Result,
    ): List<String> {
        if (parsed.characters.isEmpty()) return emptyList()
        val known = try {
            documentService.listCharacters(scenarioName).map { it.name }.toSet()
        } catch (e: Exception) {
            return emptyList() // 인물 목록을 못 읽으면 경고를 건너뛴다. 등록 자체를 막을 이유는 없다
        }
        val missing = parsed.characters.map { it.label }.distinct().filter { it !in known }
        if (missing.isEmpty()) return emptyList()
        val listed = missing.take(MAX_LISTED_MISSING).joinToString(", ")
        val rest = if (missing.size > MAX_LISTED_MISSING) " 외 ${missing.size - MAX_LISTED_MISSING}명" else ""
        return listOf(
            "이 시나리오에 인물 문서가 없는 이름입니다: $listed$rest. " +
                "이대로 등록하면 인물 이미지로 동작하지 않고 장면·배경 태그로 취급됩니다. " +
                "이름을 `characters/{이름}.md`의 파일명과 같게 맞추거나, 그 작품의 시나리오에 등록하세요.",
        )
    }

    /** `images.md`가 없으면 빈 문서로 본다. 적용할 때 새로 만든다. */
    private fun readImages(scenarioName: String): String = try {
        documentService.readDocument(scenarioName, DocumentType.IMAGES).content
    } catch (e: NotFoundException) {
        ""
    }

    companion object {
        /** 경고에 이름을 나열할 최대 인물 수 */
        const val MAX_LISTED_MISSING = 10
    }

    private fun append(existing: String, entries: List<ImageEntry>): String {
        val body = entries.joinToString("\n") { e ->
            if (e.description.isEmpty()) "- ${e.tag}: ${e.url}" else "- ${e.tag}: ${e.url} | ${e.description}"
        }
        val head = existing.trimEnd()
        return if (head.isEmpty()) "$body\n" else "$head\n\n$body\n"
    }
}
