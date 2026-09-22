package com.crack.image

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RestController

/** `GET /api/stories/{id}/images` (DESIGN.md §8.5). 프론트가 `{{img:태그}}`를 `<img>`로 바꿀 때 쓴다. */
@RestController
class ImageCatalogController(private val imageCatalogService: ImageCatalogService) {

    @GetMapping("/api/stories/{storyId}/images")
    fun images(@PathVariable storyId: Long): List<ImageEntry> = imageCatalogService.forStory(storyId)
}
