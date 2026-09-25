package com.crack.scenario.imports

import com.crack.global.config.DataPaths
import com.crack.global.exception.BadRequestException
import com.crack.memory.docs.AtomicFiles
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * 시나리오를 **원자적으로** 만든다 (DESIGN.md §11.3).
 *
 * 임시 폴더(`{data-path}/.import-tmp/{jobId}`)에 문서를 다 만든 뒤 시나리오 폴더로 옮긴다.
 * 중간에 실패하면 임시 폴더를 지워 흔적을 남기지 않는다. 폴더 이름이 `.`으로 시작하므로
 * 데이터 루트를 훑는 쪽에서도 시나리오로 보이지 않는다.
 *
 * 임시 폴더는 데이터 루트 **안**에 둔다. 같은 파일시스템이어야 `ATOMIC_MOVE`가 된다.
 */
@Component
class ImportWorkspace(private val dataPaths: DataPaths) {

    private val log = LoggerFactory.getLogger(javaClass)

    fun createTempDir(jobId: String): Path {
        val root = dataPaths.root.resolve(TEMP_DIR)
        Files.createDirectories(root)
        val dir = root.resolve("${sanitize(jobId)}-${System.currentTimeMillis()}")
        Files.createDirectories(dir.resolve("characters"))
        return dir
    }

    fun write(dir: Path, relativePath: String, content: String) {
        AtomicFiles.writeString(dir.resolve(relativePath), content)
    }

    /** 폴더 안의 파일을 상대 경로로 나열한다(정렬). `done` 이벤트의 파일 목록이다. */
    fun listFiles(dir: Path): List<String> =
        Files.walk(dir).use { stream ->
            stream.filter { Files.isRegularFile(it) }
                .map { dir.relativize(it).toString().replace('\\', '/') }
                .sorted()
                .toList()
        }

    /**
     * 이름을 쓸 수 있는지 미리 본다. 오래 도는 생성을 시작하기 전에 400을 내기 위한 것이다.
     * 최종 판정은 [publish]의 [Files.move]가 한다(원자적).
     */
    fun requireAvailable(scenarioName: String) {
        val target = dataPaths.scenarioDir(scenarioName) // 경로 조각 검사 포함
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            throw BadRequestException("이미 '$scenarioName' 폴더가 있습니다. 다른 이름을 쓰세요(덮어쓰지 않습니다)")
        }
    }

    /** 임시 폴더를 시나리오 폴더로 옮긴다. 이미 있으면 400. */
    fun publish(tempDir: Path, scenarioName: String): Path {
        val target = dataPaths.scenarioDir(scenarioName)
        Files.createDirectories(target.parent)
        try {
            try {
                Files.move(tempDir, target, StandardCopyOption.ATOMIC_MOVE)
            } catch (e: AtomicMoveNotSupportedException) {
                Files.move(tempDir, target)
            }
        } catch (e: FileAlreadyExistsException) {
            throw BadRequestException("이미 '$scenarioName' 폴더가 있습니다. 다른 이름을 쓰세요(덮어쓰지 않습니다)")
        }
        return target
    }

    /** 실패 정리. 지우다 실패해도 예외를 던지지 않는다(원래 실패 원인을 가리지 않게). */
    fun deleteQuietly(dir: Path?) {
        if (dir == null) return
        try {
            if (!Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) return
            Files.walk(dir).use { stream ->
                stream.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
            }
        } catch (e: Exception) {
            log.warn("가져오기 임시 폴더 정리 실패: {} ({})", dir, e.message)
        }
    }

    private fun sanitize(value: String): String = value.replace(Regex("""[^A-Za-z0-9_-]"""), "").ifEmpty { "job" }

    companion object {
        const val TEMP_DIR = ".import-tmp"
    }
}
