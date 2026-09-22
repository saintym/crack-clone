package com.crack.prompt.contributor

import com.crack.memory.docs.MemoryDocs
import org.springframework.stereotype.Component
import java.nio.file.Files

/** WORLD: `world.md` */
@Component
class WorldContributor : PromptContributor {
    override val slot = PromptSlot.WORLD
    override val order = 0
    override fun contribute(ctx: PromptContext): String? =
        StoryDocs.read(ctx.storyDir.resolve("world.md"))?.let { StoryDocs.titled("세계관", it) }
}

/** SCENARIO(0): `scenario.md`. 연대기는 [ChronicleContributor]가 뒤에 붙인다. */
@Component
class ScenarioContributor : PromptContributor {
    override val slot = PromptSlot.SCENARIO
    override val order = 0
    override fun contribute(ctx: PromptContext): String? =
        StoryDocs.read(ctx.storyDir.resolve("scenario.md"))?.let { StoryDocs.titled("시나리오", it) }
}

/** PROTAGONIST: `characters/protagonist.md`. 활성 인물 선택과 무관하게 항상 넣는다(§6.2). */
@Component
class ProtagonistContributor : PromptContributor {
    override val slot = PromptSlot.PROTAGONIST
    override val order = 0
    override fun contribute(ctx: PromptContext): String? =
        StoryDocs.read(MemoryDocs.protagonistPath(ctx.storyDir))?.let { StoryDocs.titled("주인공(사용자)", it) }
}

/** USER_NOTE: `user_note.md`. 없으면 T09 이전 옛 스토리가 쓰던 같은 스토리 폴더의 `memory/must_remember.md`. */
@Component
class UserNoteContributor : PromptContributor {
    override val slot = PromptSlot.USER_NOTE
    override val order = 0
    override fun contribute(ctx: PromptContext): String? {
        val note = ctx.storyDir.resolve(USER_NOTE_FILE)
        val body = if (Files.exists(note)) StoryDocs.read(note) else StoryDocs.read(ctx.storyDir.resolve(LEGACY_MUST_REMEMBER))
        return body?.let { StoryDocs.titled("유저노트", it) }
    }

    companion object {
        const val USER_NOTE_FILE = "user_note.md"
        const val LEGACY_MUST_REMEMBER = "memory/must_remember.md"
    }
}

/** BOTTOM(100): 이번 턴 지시(이어쓰기, 재생성 지시, `/` 명령). 지속 지시(T16)는 order 0으로 이 앞에 온다. */
@Component
class TurnInstructionContributor : PromptContributor {
    override val slot = PromptSlot.BOTTOM
    override val order = 100
    override fun contribute(ctx: PromptContext): String? = ctx.turnInstruction?.trim()?.takeIf { it.isNotEmpty() }
}
