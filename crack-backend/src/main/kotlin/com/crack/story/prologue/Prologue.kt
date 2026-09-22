package com.crack.story.prologue

import com.crack.memory.docs.MemoryDocs
import java.nio.file.Files
import java.nio.file.Path

/**
 * 첫 메시지(프롤로그) 원본 `prologue.md` 읽기와 치환 (DESIGN.md §2, §3 / D16).
 *
 * 순수 파일 라이브러리다. 메시지 저장은 [PrologueService]가 한다.
 */
object Prologue {

    const val FILE_NAME = "prologue.md"

    /** 주인공 이름이 없을 때 `{{user}}` 대신 넣는 말. */
    const val DEFAULT_USER_NAME = "당신"

    /** `{{user}}` 자리표시자. 대소문자와 중괄호 안쪽 공백은 가리지 않는다(`{{ User }}`도 된다). */
    private val USER_PLACEHOLDER = Regex("""\{\{\s*user\s*}}""", RegexOption.IGNORE_CASE)

    /** HTML 주석. 템플릿의 안내 문구를 주석으로 두면 첫 메시지에 들어가지 않는다. */
    private val HTML_COMMENT = Regex("""<!--.*?-->""", RegexOption.DOT_MATCHES_ALL)

    /**
     * 스토리 폴더의 `prologue.md`를 읽어 첫 메시지 본문을 만든다.
     * 파일이 없거나, 주석을 빼고 나면 비어 있으면 null.
     */
    fun read(storyDir: Path): String? {
        val file = storyDir.resolve(FILE_NAME)
        if (!Files.isRegularFile(file)) return null
        return render(Files.readString(file), userName(storyDir))
    }

    /** 원문에서 HTML 주석을 빼고 `{{user}}`를 [userName]으로 바꾼 뒤 앞뒤 공백을 잘라낸다. 결과가 비면 null. */
    fun render(raw: String, userName: String): String? {
        val body = HTML_COMMENT.replace(raw.replace("\r\n", "\n"), "").trim()
        if (body.isEmpty()) return null
        return USER_PLACEHOLDER.replace(body) { userName }
    }

    /**
     * `characters/protagonist.md`의 `- **이름**:` 값. 파일이나 값이 없으면 [DEFAULT_USER_NAME].
     * 괄호로 감싼 안내 문구(`(주인공 이름)`)도 값이 없는 것으로 본다.
     */
    fun userName(storyDir: Path): String {
        val name = MemoryDocs.readProtagonist(storyDir)?.displayName()?.trim()
        if (name.isNullOrEmpty() || (name.startsWith("(") && name.endsWith(")"))) return DEFAULT_USER_NAME
        return name
    }
}
