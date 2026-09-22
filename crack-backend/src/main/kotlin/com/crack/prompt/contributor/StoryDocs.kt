package com.crack.prompt.contributor

import java.nio.file.Files
import java.nio.file.Path

/** 기여자가 스토리 폴더의 문서를 읽을 때 쓰는 도우미. */
internal object StoryDocs {
    /** 파일이 없거나, 일반 파일이 아니거나, 내용이 공백뿐이면 null. */
    fun read(path: Path): String? {
        if (!Files.isRegularFile(path)) return null
        return Files.readString(path).takeIf { it.isNotBlank() }
    }

    /** `=== 제목 ===` 머리를 붙인다. 본문 끝의 줄바꿈은 뗀다. */
    fun titled(title: String, body: String): String = "=== $title ===\n${body.trimEnd()}"
}
