package com.crack.status

import com.crack.memory.docs.CharacterDoc
import com.crack.memory.docs.MemoryDocs
import com.crack.memory.docs.StoryState
import com.crack.story.files.StoryDirs
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.nio.file.Path

/**
 * 인물 상태 패널용 데이터 (DESIGN.md §7.5, D13).
 *
 * 스토리 폴더의 `state.json`, 주인공 `## 변화 기록`, 인물 `## 기억`을 T05 파서로 읽어 구조체로 만든다.
 * LLM을 부르지 않고 파일만 읽는다. 스토리 폴더만 본다(D12).
 */
@Service
class StoryStatusService(
    private val storyDirs: StoryDirs,
) {

    fun status(storyId: Long): StoryStatusResponse {
        val location = storyDirs.locate(storyId)
        return build(location.dir, location.story.recordedThroughTurn)
    }

    companion object {
        private val log = LoggerFactory.getLogger(StoryStatusService::class.java)

        const val DEFAULT_PROTAGONIST_NAME = "주인공"

        /** 스토리 폴더 [storyDir]에서 상태를 만든다. 폴더나 파일이 없으면 빈 값으로 채운다. */
        fun build(storyDir: Path, recordedThroughTurn: Int): StoryStatusResponse {
            val state = readStateSafely(storyDir)
            val protagonist = MemoryDocs.readProtagonist(storyDir)?.let { doc ->
                ProtagonistStatus.of(doc.displayName() ?: DEFAULT_PROTAGONIST_NAME, doc.changes())
            }
            return StoryStatusResponse(
                recordedThroughTurn = recordedThroughTurn,
                state = state,
                protagonist = protagonist,
                characters = characters(MemoryDocs.readCharacters(storyDir), state.companions),
            )
        }

        /**
         * 기억이 있는 인물만, 동행 인물(동행 목록 순서) → 나머지(이름순).
         * 동행 목록의 이름은 파일명 또는 별칭과 같으면 그 인물로 본다.
         */
        internal fun characters(docs: List<CharacterDoc>, companions: List<String>): List<CharacterStatus> {
            val withMemory = docs.filter { !it.memory().isEmpty() }
            val companionIndex = mutableMapOf<String, Int>()
            for (doc in withMemory) {
                val names = listOf(doc.name) + doc.parseAliases()
                val index = companions.indexOfFirst { c -> names.any { it == c.trim() } }
                if (index >= 0) companionIndex[doc.name] = index
            }
            return withMemory
                .sortedWith(compareBy<CharacterDoc> { companionIndex[it.name] ?: Int.MAX_VALUE }.thenBy { it.name })
                .map { CharacterStatus.of(it, companion = it.name in companionIndex) }
        }

        /** 깨진 `state.json` 때문에 패널 전체가 실패하지 않도록 빈 기본값을 쓴다. */
        private fun readStateSafely(storyDir: Path): StoryState =
            try {
                MemoryDocs.readState(storyDir)
            } catch (e: Exception) {
                log.warn("state.json을 읽지 못해 빈 상태로 보여 줍니다: {}", storyDir, e)
                StoryState.EMPTY
            }
    }
}
