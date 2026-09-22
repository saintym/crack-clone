package com.crack.memory.docs

import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * 원자적 파일 쓰기. 같은 폴더의 임시 파일에 다 쓴 뒤 `ATOMIC_MOVE`로 바꿔치기한다.
 * 쓰는 도중 실패하거나 프로세스가 죽어도 대상 파일은 이전 내용 그대로 남는다.
 */
object AtomicFiles {

    fun writeString(target: Path, content: String) = writeBytes(target, content.toByteArray(Charsets.UTF_8))

    fun writeBytes(target: Path, bytes: ByteArray) {
        val absolute = target.toAbsolutePath()
        val dir = absolute.parent
        Files.createDirectories(dir)
        // 같은 폴더(같은 파일시스템)에 만들어야 원자적 이동이 가능하다
        val tmp = Files.createTempFile(dir, ".${absolute.fileName}.", ".tmp")
        try {
            Files.write(tmp, bytes)
            try {
                Files.move(tmp, absolute, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (e: AtomicMoveNotSupportedException) {
                Files.move(tmp, absolute, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(tmp)
        }
    }

    /** 파일이 없으면 null. */
    fun readStringOrNull(path: Path): String? =
        if (Files.isRegularFile(path)) Files.readString(path) else null
}
