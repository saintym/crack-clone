package com.crack.status

import com.crack.memory.docs.CharacterDoc
import com.crack.memory.docs.EventEntry
import com.crack.memory.docs.MemoryItem
import com.crack.memory.docs.ProtagonistChanges
import com.crack.memory.docs.RelationEntry
import com.crack.memory.docs.StoryState

/** `GET /api/stories/{storyId}/status` 응답 (DESIGN.md §7.5). */
data class StoryStatusResponse(
    val recordedThroughTurn: Int,
    val state: StoryState,
    /** 주인공 문서가 없으면 null */
    val protagonist: ProtagonistStatus?,
    /** 기억이 있는 인물만. 동행 인물 → 나머지 이름순 */
    val characters: List<CharacterStatus>,
)

/** 주인공 `## 변화 기록` 하위 섹션 */
data class ProtagonistStatus(
    val name: String,
    val relations: List<RelationDto>,
    val statsAndSkills: List<ItemDto>,
    val possessions: List<ItemDto>,
    val body: List<ItemDto>,
) {
    companion object {
        fun of(name: String, changes: ProtagonistChanges) = ProtagonistStatus(
            name = name,
            relations = changes.relations.map(RelationDto::of),
            statsAndSkills = changes.statsAndSkills.map(ItemDto::of),
            possessions = changes.possessions.map(ItemDto::of),
            body = changes.body.map(ItemDto::of),
        )
    }
}

/** 인물 `## 기억` 하위 섹션 */
data class CharacterStatus(
    val name: String,
    val companion: Boolean,
    val relations: List<RelationDto>,
    /** 문서 순서 그대로 전부 */
    val events: List<EventDto>,
    /** `### 소지품·기술·신체` */
    val possessions: List<ItemDto>,
) {
    companion object {
        fun of(doc: CharacterDoc, companion: Boolean): CharacterStatus {
            val memory = doc.memory()
            return CharacterStatus(
                name = doc.name,
                companion = companion,
                relations = memory.relations.map(RelationDto::of),
                events = memory.events.map(EventDto::of),
                possessions = memory.possessions.map(ItemDto::of),
            )
        }
    }
}

data class ItemDto(val text: String, val turns: List<Int>) {
    companion object {
        fun of(item: MemoryItem) = ItemDto(item.text, item.turns)
    }
}

data class RelationDto(val target: String, val description: String, val turns: List<Int>) {
    companion object {
        fun of(entry: RelationEntry) = RelationDto(entry.target, entry.description, entry.turns)
    }
}

data class EventDto(val fromTurn: Int?, val toTurn: Int?, val description: String) {
    companion object {
        fun of(entry: EventEntry) = EventDto(entry.fromTurn, entry.toTurn, entry.description)
    }
}
