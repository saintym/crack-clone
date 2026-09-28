package com.crack.document.story

import com.crack.chat.flow.AfterTurnEvent
import com.crack.chat.flow.AfterTurnHook
import com.crack.memory.docs.MemoryDocs
import com.crack.story.files.StoryDirs
import org.slf4j.LoggerFactory
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Path

/**
 * "설정이 방금 바뀌었다" 신호를 켜고 끈다 (T39, D41).
 *
 * - **켜기**: 사용자가 스토리 문서를 저장하면 [mark]가 `state.json.changedDocs`에 경로를 담는다.
 * - **끄기**: 응답이 저장되면 [SettingsChangedHook]이 비운다. 신호는 **다음 응답 한 번에만** 쓰인다.
 *
 * 기록 파이프라인이 쓴 문서는 담기지 않는다. 이 경로는 사용자 편집 API만 지난다.
 */
@Component
class SettingsChangedMarker(private val storyDirs: StoryDirs) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** 한 스토리에 쌓아 둘 경로 수 상한. 넘으면 오래된 것부터 버린다 */
    private val maxPaths = 32

    @Synchronized
    fun mark(storyId: Long, path: String) = update(storyId) { state ->
        if (state.changedDocs.contains(path)) state
        else state.copy(changedDocs = (state.changedDocs + path).takeLast(maxPaths))
    }

    @Synchronized
    fun clear(storyId: Long) = update(storyId) { state ->
        if (state.changedDocs.isEmpty()) state else state.copy(changedDocs = emptyList())
    }

    private fun update(storyId: Long, change: (com.crack.memory.docs.StoryState) -> com.crack.memory.docs.StoryState) {
        val dir = storyDir(storyId) ?: return
        try {
            val state = MemoryDocs.readState(dir)
            val next = change(state)
            if (next != state) MemoryDocs.writeState(dir, next)
        } catch (e: Exception) {
            // 신호를 못 남겨도 플레이는 계속돼야 한다. 문서 저장 자체는 이미 끝났다.
            log.warn("설정 변경 신호를 갱신하지 못했습니다(story={}): {}", storyId, e.message)
        }
    }

    private fun storyDir(storyId: Long): Path? = try {
        storyDirs.locate(storyId).dir.takeIf { Files.isDirectory(it) }
    } catch (e: Exception) {
        log.warn("스토리 폴더를 찾지 못했습니다(story={}): {}", storyId, e.message)
        null
    }
}

/** 응답이 저장되면 신호를 끈다. 한 번 쓰이고 사라져야 캐시 접두사와 프롬프트가 안정된다. */
@Component
@Order(200)
class SettingsChangedHook(private val marker: SettingsChangedMarker) : AfterTurnHook {
    override fun afterTurn(event: AfterTurnEvent) = marker.clear(event.storyId)
}
