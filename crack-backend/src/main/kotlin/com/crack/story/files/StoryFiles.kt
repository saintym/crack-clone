package com.crack.story.files

import com.crack.memory.docs.AtomicFiles
import com.crack.memory.docs.MemoryDocs
import com.crack.memory.docs.StoryState
import org.slf4j.LoggerFactory
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * 스토리 폴더 만들기와 지우기 (DESIGN.md §1, §2 / D12 스토리 격리).
 *
 * 스토리를 만들 때 시나리오 원본 문서를 **통째로 복사**한다. 이후 플레이 중에는 스토리 폴더만 읽고 쓴다.
 * 링크를 만들지 않고 내용을 복사하므로(심볼릭 링크도 따라가서 내용만 복사) 스토리끼리, 스토리와 원본이 어떤 파일도 공유하지 않는다.
 *
 * 순수 파일 라이브러리다. DB와 경로 계산(`DataPaths`)은 호출하는 쪽이 한다.
 */
object StoryFiles {

    private val log = LoggerFactory.getLogger(StoryFiles::class.java)

    const val STORY_JSON = "story.json"
    const val USER_NOTE_FILE = "user_note.md"
    const val DIRECTIVES_FILE = "directives.json"
    const val CHARACTERS_DIR = MemoryDocs.CHARACTERS_DIR

    /** 원본에서 복사하는 최상위 문서. 없으면 건너뛴다. `images.md`는 원본을 참조하므로 복사하지 않는다(§8.5). */
    val COPIED_FILES = listOf("world.md", "scenario.md", "prologue.md", "keywords.md", "commands.md")

    const val USER_NOTE_INITIAL = "# 유저노트\n\n"
    const val CHRONICLE_INITIAL = "# 연대기\n"
    const val DIRECTIVES_INITIAL = "[]\n"

    /**
     * 시나리오 원본으로 새 스토리 폴더를 만든다.
     *
     * 1. `COPIED_FILES`와 `characters/{이름}.md`(주인공 포함)를 복사한다. 없는 파일은 건너뛴다.
     *    `images.md`, `stories/`, 옛 `chat/`·`memory/` 같은 나머지는 복사하지 않는다.
     * 2. `user_note.md`, `chronicle.md`, `directives.json`(`[]`), `state.json`(빈 상태)을 만든다.
     * 3. 마지막에 `story.json`을 쓴다. `story.json`이 있으면 폴더가 끝까지 만들어졌다는 뜻이다.
     *
     * 스토리 폴더가 이미 있으면 [FileAlreadyExistsException]으로 거부한다(다른 스토리를 덮어쓰지 않기 위해).
     * 그 밖의 이유로 중간에 실패하면 예외를 그대로 던진다. 정리는 호출하는 쪽이 [deleteStoryDir]로 한다.
     */
    fun initFromScenario(scenarioDir: Path, storyDir: Path): StoryMeta {
        require(storyDir.toAbsolutePath().normalize() != scenarioDir.toAbsolutePath().normalize()) {
            "스토리 폴더가 시나리오 폴더와 같을 수 없습니다: $storyDir"
        }
        if (!Files.isDirectory(scenarioDir)) {
            log.warn("시나리오 폴더가 없어 원본 문서 없이 스토리를 만든다: {}", scenarioDir)
        }

        Files.createDirectories(storyDir.toAbsolutePath().parent)
        Files.createDirectory(storyDir) // 이미 있으면 FileAlreadyExistsException (원자적 검사)
        Files.createDirectories(storyDir.resolve(CHARACTERS_DIR))

        // 1. 원본 복사
        for (name in COPIED_FILES) {
            copyIfRegularFile(scenarioDir.resolve(name), storyDir.resolve(name))
        }
        for (source in listCharacterFiles(scenarioDir.resolve(CHARACTERS_DIR))) {
            copyIfRegularFile(source, storyDir.resolve(CHARACTERS_DIR).resolve(source.fileName.toString()))
        }

        // 2. 스토리 전용 파일
        AtomicFiles.writeString(storyDir.resolve(USER_NOTE_FILE), USER_NOTE_INITIAL)
        AtomicFiles.writeString(MemoryDocs.chroniclePath(storyDir), CHRONICLE_INITIAL)
        AtomicFiles.writeString(storyDir.resolve(DIRECTIVES_FILE), DIRECTIVES_INITIAL)
        MemoryDocs.writeState(storyDir, StoryState.EMPTY)

        // 3. 완료 표시
        val meta = StoryMeta.create(scenarioDir.fileName.toString())
        meta.write(storyDir.resolve(STORY_JSON))
        return meta
    }

    /** `story.json`을 읽는다. 없으면(T08 이전 스토리나 `_legacy`) null. */
    fun readMeta(storyDir: Path): StoryMeta? =
        storyDir.resolve(STORY_JSON).takeIf { Files.isRegularFile(it) }?.let { StoryMeta.read(it) }

    /**
     * 스토리 폴더를 통째로 지운다. 심볼릭 링크는 따라가지 않고 링크 자체만 지운다.
     * [storiesRoot] 바깥 경로나 `storiesRoot` 자체는 거부한다(원본이나 다른 시나리오를 지우지 않기 위해).
     */
    fun deleteStoryDir(storiesRoot: Path, storyDir: Path) {
        val root = storiesRoot.toAbsolutePath().normalize()
        val target = storyDir.toAbsolutePath().normalize()
        require(target.parent == root) { "스토리 폴더가 아닌 경로는 지울 수 없습니다: $storyDir" }
        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) return
        Files.walk(target).use { stream ->
            stream.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }

    private fun listCharacterFiles(dir: Path): List<Path> {
        if (!Files.isDirectory(dir)) return emptyList()
        return Files.list(dir).use { stream ->
            stream.filter { p ->
                val name = p.fileName.toString()
                name.endsWith(".md") && !name.startsWith(".") && Files.isRegularFile(p)
            }.sorted().toList()
        }
    }

    private fun copyIfRegularFile(source: Path, target: Path) {
        if (!Files.isRegularFile(source)) return
        // 링크를 복사하지 않고 내용을 복사한다(Files.copy는 기본으로 심볼릭 링크를 따라간다)
        Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING)
    }
}
