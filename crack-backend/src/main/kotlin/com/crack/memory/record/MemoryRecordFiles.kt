package com.crack.memory.record

import com.crack.memory.docs.AtomicFiles
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import java.nio.file.Files
import java.nio.file.Path

/**
 * 기록 스냅샷과 파일 반영 (DESIGN.md §2 `memory/history/{recordId}/before/...`).
 *
 * - `before/{상대 경로}`: 기록 직전 내용. 바뀐 파일만 둔다.
 * - `created.json`: 기록 전에 없던 파일(되돌릴 때 지운다)의 상대 경로 배열.
 *
 * 경로는 스토리 폴더 기준 상대 경로이며, 폴더 밖을 가리키면 거부한다.
 */
object MemoryRecordFiles {

    const val HISTORY_DIR = "memory/history"
    private const val BEFORE_DIR = "before"
    private const val CREATED_FILE = "created.json"
    private val MAPPER = jacksonObjectMapper()

    fun historyDir(storyDir: Path, recordId: Long): Path = storyDir.resolve(HISTORY_DIR).resolve(recordId.toString())

    fun resolve(storyDir: Path, rel: String): Path {
        val root = storyDir.toAbsolutePath().normalize()
        val target = root.resolve(rel).normalize()
        require(target.startsWith(root) && target != root) { "스토리 폴더 밖 경로입니다: $rel" }
        return target
    }

    fun read(storyDir: Path, rel: String): String? = AtomicFiles.readStringOrNull(resolve(storyDir, rel))

    /**
     * 스냅샷을 저장한다. [before]는 상대 경로 → 기록 직전 내용(없던 파일이면 null).
     * 같은 기록 ID의 이전 스냅샷이 있으면 지우고 새로 만든다.
     */
    fun saveSnapshot(storyDir: Path, recordId: Long, before: Map<String, String?>) {
        val dir = historyDir(storyDir, recordId)
        deleteRecursively(dir)
        val beforeDir = dir.resolve(BEFORE_DIR)
        Files.createDirectories(beforeDir)
        before.forEach { (rel, content) -> if (content != null) AtomicFiles.writeString(resolve(beforeDir, rel), content) }
        AtomicFiles.writeString(dir.resolve(CREATED_FILE), MAPPER.writeValueAsString(before.filterValues { it == null }.keys.toList()))
    }

    /** 스냅샷에 저장된 기록 직전 내용. 기록 전에 없던 파일이면 null. 스냅샷이 없으면 [MissingSnapshotException]. */
    fun snapshotOf(storyDir: Path, recordId: Long, rel: String): String? {
        val dir = historyDir(storyDir, recordId)
        if (rel in createdFiles(storyDir, recordId)) return null
        return AtomicFiles.readStringOrNull(resolve(dir.resolve(BEFORE_DIR), rel))
            ?: throw MissingSnapshotException("기록 $recordId 의 스냅샷이 없습니다: $rel")
    }

    fun hasSnapshot(storyDir: Path, recordId: Long): Boolean = Files.isRegularFile(historyDir(storyDir, recordId).resolve(CREATED_FILE))

    private fun createdFiles(storyDir: Path, recordId: Long): List<String> {
        val file = historyDir(storyDir, recordId).resolve(CREATED_FILE)
        if (!Files.isRegularFile(file)) throw MissingSnapshotException("기록 $recordId 의 스냅샷이 없습니다")
        return MAPPER.readValue(Files.readString(file))
    }

    /** 새 내용을 쓴다. 하나라도 실패하면 이미 쓴 파일을 [before]로 되돌리고 예외를 다시 던진다. */
    fun writeAll(storyDir: Path, contents: Map<String, String>, before: Map<String, String?>) {
        val written = mutableListOf<String>()
        try {
            contents.forEach { (rel, content) ->
                AtomicFiles.writeString(resolve(storyDir, rel), content)
                written += rel
            }
        } catch (e: Exception) {
            try {
                restore(storyDir, written.associateWith { before[it] })
            } catch (restoreError: Exception) {
                e.addSuppressed(restoreError)
            }
            throw e
        }
    }

    /** [contents]로 되돌린다. 값이 null이면 파일을 지운다. */
    fun restore(storyDir: Path, contents: Map<String, String?>) {
        contents.forEach { (rel, content) ->
            val target = resolve(storyDir, rel)
            if (content == null) Files.deleteIfExists(target) else AtomicFiles.writeString(target, content)
        }
    }

    private fun deleteRecursively(dir: Path) {
        if (!Files.exists(dir)) return
        Files.walk(dir).use { s -> s.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
    }
}

class MissingSnapshotException(message: String) : RuntimeException(message)
