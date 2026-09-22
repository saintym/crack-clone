package com.crack.story.files

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/** 테스트 픽스처 `src/test/resources/fixtures/sample-scenario`를 임시 폴더로 복사한다. 픽스처 원본은 건드리지 않는다. */
object SampleScenario {

    val source: Path by lazy {
        val url = requireNotNull(SampleScenario::class.java.getResource("/fixtures/sample-scenario")) {
            "fixtures/sample-scenario 픽스처가 클래스패스에 없습니다"
        }
        Paths.get(url.toURI())
    }

    /** [target] 폴더(없으면 만든다)에 픽스처 내용을 복사하고 [target]을 돌려준다. */
    fun copyTo(target: Path): Path {
        Files.walk(source).use { stream ->
            stream.forEach { src ->
                val dst = target.resolve(source.relativize(src).toString())
                if (Files.isDirectory(src)) Files.createDirectories(dst) else Files.copy(src, dst)
            }
        }
        return target
    }
}
