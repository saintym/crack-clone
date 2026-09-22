package com.crack.migration.legacy

import com.crack.memory.docs.Chronicle
import com.crack.story.files.StoryFiles
import java.nio.file.Files
import java.nio.file.Path

/**
 * 옛 스토리 폴더 구조를 읽고 v2 문서로 바꾸는 순수 파일 함수 모음. DB는 다루지 않는다.
 *
 * 옛 구조(스토리 폴더 기준):
 * - `chat/chat_latest.md`, `chat/archive/turn_{from}_{to}.md`
 * - `memory/must_remember.md`, `memory/summary_{from}_{to}.md`
 * - `characters/{이름}.md` (기억이 갱신된 인물만 있는 스토리별 덮어쓰기)
 *
 * 옮긴 옛 파일은 `legacy/` 아래에 보존한다. 그래서 옛 파일을 찾을 때는 원래 위치를 먼저 보고, 없으면 `legacy/` 아래를 본다
 * (이전이 중간에 끊긴 뒤 다시 실행하는 경우).
 */
object LegacyFiles {

    const val CHAT_DIR = "chat"
    const val MEMORY_DIR = "memory"
    const val LEGACY_DIR = "legacy"
    const val CHAT_LATEST = "chat_latest.md"
    const val ARCHIVE_DIR = "archive"
    const val MUST_REMEMBER = "must_remember.md"

    /** 이전으로 `legacy/` 아래로 옮기는 옛 폴더. */
    val MOVED_DIRS = listOf(CHAT_DIR, MEMORY_DIR)

    private val ARCHIVE_NAME = Regex("""turn_(\d+)(?:_(\d+))?\.md""")
    private val SUMMARY_NAME = Regex("""summary_(\d+)_(\d+)\.md""")
    private val HEADING = Regex("""^\s{0,3}#{1,6}\s+(.*?)\s*#*\s*$""")
    private val MUST_REMEMBER_TITLE = Regex("""^#\s*필수\s*기억\s*사항\s*$""")
    private const val MUST_REMEMBER_GUIDE = "(사용자가 직접 입력하는"

    /** 옛 폴더 [name](`chat` 또는 `memory`)의 실제 위치. 원래 위치, 없으면 `legacy/` 아래. 둘 다 없으면 null. */
    fun oldDir(storyDir: Path, name: String): Path? =
        listOf(storyDir.resolve(name), storyDir.resolve(LEGACY_DIR).resolve(name)).firstOrNull { Files.isDirectory(it) }

    /**
     * 가져올 대화 파일 목록. `chat/archive/turn_*.md`를 번호 순(시작 턴, 끝 턴, 이름)으로, 그다음 `chat/chat_latest.md`.
     * 번호를 읽을 수 없는 `turn_*.md`는 번호 있는 파일 뒤에 이름순으로 둔다.
     */
    fun chatFiles(storyDir: Path): List<Path> {
        val chatDir = oldDir(storyDir, CHAT_DIR) ?: return emptyList()
        val archiveDir = chatDir.resolve(ARCHIVE_DIR)
        val archives = if (Files.isDirectory(archiveDir)) {
            Files.list(archiveDir).use { s ->
                s.filter { Files.isRegularFile(it) }
                    .filter { val n = it.fileName.toString(); n.startsWith("turn_") && n.endsWith(".md") }
                    .toList()
            }.sortedWith(
                compareBy<Path>(
                    { archiveKey(it)?.first ?: Int.MAX_VALUE },
                    { archiveKey(it)?.second ?: Int.MAX_VALUE },
                    { it.fileName.toString() },
                )
            )
        } else emptyList()
        val latest = chatDir.resolve(CHAT_LATEST).takeIf { Files.isRegularFile(it) }
        return archives + listOfNotNull(latest)
    }

    private fun archiveKey(path: Path): Pair<Int, Int>? {
        val m = ARCHIVE_NAME.matchEntire(path.fileName.toString()) ?: return null
        val from = m.groupValues[1].toIntOrNull() ?: return null
        return from to (m.groupValues[2].toIntOrNull() ?: from)
    }

    fun mustRememberFile(storyDir: Path): Path? =
        oldDir(storyDir, MEMORY_DIR)?.resolve(MUST_REMEMBER)?.takeIf { Files.isRegularFile(it) }

    /**
     * 옛 필수 기억사항을 유저노트로 바꾼다.
     * 제목 `# 필수 기억사항`은 `# 유저노트`로, 옛 템플릿의 안내 문구 줄은 뺀다. 남는 내용이 없으면(템플릿 그대로) 빈 유저노트.
     */
    fun toUserNote(mustRemember: String): String {
        val lines = mustRemember.lines().map { it.removeSuffix("\r") }.toMutableList()
        val first = lines.indexOfFirst { it.isNotBlank() }
        if (first >= 0 && MUST_REMEMBER_TITLE.matches(lines[first].trim())) lines.removeAt(first)
        val body = lines.filterNot { it.trim().startsWith(MUST_REMEMBER_GUIDE) }.joinToString("\n").trim()
        if (body.all { it == '-' || it.isWhitespace() }) return StoryFiles.USER_NOTE_INITIAL
        return StoryFiles.USER_NOTE_INITIAL + body + "\n"
    }

    /** `memory/summary_*.md`를 시작 턴 순으로. */
    fun summaryFiles(storyDir: Path): List<Path> {
        val memoryDir = oldDir(storyDir, MEMORY_DIR) ?: return emptyList()
        return Files.list(memoryDir).use { s ->
            s.filter { Files.isRegularFile(it) && SUMMARY_NAME.matches(it.fileName.toString()) }.toList()
        }.sortedWith(
            compareBy<Path>(
                { SUMMARY_NAME.matchEntire(it.fileName.toString())!!.groupValues[1].toInt() },
                { SUMMARY_NAME.matchEntire(it.fileName.toString())!!.groupValues[2].toInt() },
            )
        )
    }

    /**
     * 옛 요약 파일들을 합쳐 연대기 초안을 만든다. 요약이 없으면 빈 연대기(`# 연대기`).
     *
     * 요약마다 `**턴 a–b**` 소제목 아래 본문을 두고 전체를 `## 장 요약`에 넣는다.
     * 요약 본문의 마크다운 제목 줄은 굵은 글씨로 바꾼다. 그대로 두면 연대기의 `##` 섹션 구조가 깨지기 때문이다.
     * 옛 턴 번호(응답 수 기준)는 새 턴 번호와 다를 수 있으므로 초안으로만 쓴다.
     */
    fun chronicleFromSummaries(summaries: List<Pair<String, String>>): String {
        if (summaries.isEmpty()) return StoryFiles.CHRONICLE_INITIAL
        val merged = summaries.joinToString("\n\n") { (fileName, text) ->
            val m = SUMMARY_NAME.matchEntire(fileName)
            val title = if (m != null) "턴 ${m.groupValues[1].toInt()}–${m.groupValues[2].toInt()}" else fileName
            "**$title**\n\n" + summaryBody(text)
        }
        return Chronicle.empty().replaceOldestWithSummary(0, merged).text
    }

    /** 옛 요약 파일 본문: 첫 제목 줄(`# 1~10턴 요약`)을 빼고, 나머지 제목 줄은 굵은 글씨로 바꾼다. */
    private fun summaryBody(text: String): String {
        val lines = text.lines().map { it.removeSuffix("\r") }.toMutableList()
        val first = lines.indexOfFirst { it.isNotBlank() }
        if (first >= 0 && HEADING.matches(lines[first])) lines.removeAt(first)
        return lines.joinToString("\n") { line ->
            HEADING.matchEntire(line)?.let { "**${it.groupValues[1]}**" } ?: line
        }.trim()
    }
}
