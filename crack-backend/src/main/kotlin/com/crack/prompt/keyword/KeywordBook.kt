package com.crack.prompt.keyword

import com.crack.prompt.config.PromptProperties
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.util.concurrent.ConcurrentHashMap

/**
 * 스토리의 키워드북(`keywords.md`)에서 이번 턴에 발동할 항목을 고른다 (DESIGN.md §8.3, D11).
 *
 * - [KeywordBookParser]로 읽은 항목을 [KeywordMatcher]로 `recentText`에 매칭한다. 파일 순서가 우선순위다(위가 먼저).
 * - 최대 `crack.prompt.keyword-max-active`(기본 3)개. 0 이하면 아무것도 발동하지 않는다.
 * - 파일이 없거나 읽지 못하면 빈 목록이다(응답 생성은 계속된다).
 * - 파싱 결과는 **파일 경로별로 수정 시각과 크기**가 같으면 재사용한다. 기여자와 조립기(발동 목록 보고)가 한 턴에
 *   두 번 부르므로 두 번째는 파일을 다시 파싱하지 않는다. 캐시 키가 스토리 폴더 안의 경로라 스토리끼리 섞이지 않는다.
 */
@Component
class KeywordBook(
    private val matcher: KeywordMatcher,
    private val properties: PromptProperties,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    private data class Cached(val modified: FileTime, val size: Long, val entries: List<KeywordBookEntry>)

    private val cache = ConcurrentHashMap<Path, Cached>()

    /** [storyDir]의 키워드북에서 [recentText]에 언급된 항목을 우선순위대로 최대 `keyword-max-active`개 돌려준다. */
    fun select(storyDir: Path, recentText: String): List<KeywordBookEntry> {
        val limit = properties.keywordMaxActive
        if (limit <= 0 || recentText.isBlank()) return emptyList()
        val entries = entries(storyDir)
        if (entries.isEmpty()) return emptyList()
        val byId = entries.associateBy { it.id }
        return matcher.match(entries.map { it.toKeywordEntry() }, recentText, limit).map { byId.getValue(it) }
    }

    /** [storyDir]의 `keywords.md` 전체 항목. 파일이 없거나 읽지 못하면 빈 목록. */
    fun entries(storyDir: Path): List<KeywordBookEntry> {
        val file = storyDir.resolve(FILE_NAME).toAbsolutePath().normalize()
        return try {
            if (!Files.isRegularFile(file)) {
                cache.remove(file)
                return emptyList()
            }
            val modified = Files.getLastModifiedTime(file)
            val size = Files.size(file)
            val hit = cache[file]
            if (hit != null && hit.modified == modified && hit.size == size) return hit.entries
            val entries = KeywordBookParser.parse(Files.readString(file))
            cache[file] = Cached(modified, size, entries)
            entries
        } catch (e: Exception) {
            log.warn("키워드북을 읽지 못해 건너뜁니다: {} ({})", file, e.message)
            emptyList()
        }
    }

    companion object {
        const val FILE_NAME = "keywords.md"
    }
}
