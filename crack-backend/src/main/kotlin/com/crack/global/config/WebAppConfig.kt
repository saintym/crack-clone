package com.crack.global.config

import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration
import org.springframework.core.io.FileSystemResource
import org.springframework.core.io.Resource
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer
import org.springframework.web.servlet.resource.PathResourceResolver
import java.nio.file.Files
import java.nio.file.Path

/**
 * 프론트 빌드 결과를 서빙할 폴더. 비어 있으면 서빙하지 않는다.
 *
 * 로컬 개발은 Vite 개발 서버(:5173)를 쓰므로 비워 둔다.
 * 밖으로 열 때만 값을 주면 백엔드 포트 하나로 화면과 API가 같이 나간다(T34).
 */
@ConfigurationProperties(prefix = "crack.web")
data class WebAppProperties(val distPath: String = "")

/**
 * 프론트 정적 파일 서빙 + SPA 폴백 (T34).
 *
 * **왜 백엔드가 서빙하나:** 밖으로 열 때 Vite **개발** 서버를 노출하면 안 된다(HMR, 소스맵, 무방비).
 * 빌드 결과를 백엔드가 직접 주면 공개 포트가 하나로 줄고, 같은 출처라 CORS도 필요 없다.
 *
 * - 실제 파일이 있으면 그 파일을 준다.
 * - 없으면 `index.html`을 준다. React Router의 `/chat/12` 같은 경로가 새로고침돼도 화면이 뜬다.
 * - **`/api`로 시작하는 경로는 폴백하지 않는다.** 없는 API는 404여야 한다.
 */
@Configuration
@EnableConfigurationProperties(WebAppProperties::class)
class WebAppConfig(private val properties: WebAppProperties) : WebMvcConfigurer {
    private val log = LoggerFactory.getLogger(javaClass)

    /** 서빙할 `dist` 폴더. 설정이 비었거나 `index.html`이 없으면 null이다. */
    private val dist: Path? = resolveDist()

    private fun resolveDist(): Path? {
        val configured = properties.distPath.trim()
        if (configured.isEmpty()) return null
        val dir = Path.of(configured).toAbsolutePath().normalize()
        if (!Files.isRegularFile(dir.resolve(INDEX))) {
            log.warn("crack.web.dist-path에 {}가 없어 화면을 서빙하지 않습니다: {}", INDEX, dir)
            return null
        }
        log.info("프론트 빌드를 서빙합니다: {}", dir)
        return dir
    }

    override fun addResourceHandlers(registry: ResourceHandlerRegistry) {
        val dir = dist ?: return
        registry.addResourceHandler("/**")
            .addResourceLocations(dir.toUri().toString())
            .resourceChain(true)
            .addResolver(SpaResolver(dir.resolve(INDEX)))
    }

    /**
     * 루트(`/`)는 정적 리소스 경로가 빈 문자열이라 리졸버까지 오지 않는다. 명시적으로 `index.html`로 보낸다.
     */
    override fun addViewControllers(registry: ViewControllerRegistry) {
        if (dist == null) return
        registry.addViewController("/").setViewName("forward:/$INDEX")
    }

    /** 파일이 없으면 `index.html`로 보낸다. `/api`는 예외다. */
    private class SpaResolver(private val index: Path) : PathResourceResolver() {
        override fun getResource(resourcePath: String, location: Resource): Resource? {
            super.getResource(resourcePath, location)?.let { return it }
            if (resourcePath.startsWith("api/") || resourcePath == "api") return null
            return FileSystemResource(index)
        }
    }

    companion object {
        const val INDEX = "index.html"
    }
}
