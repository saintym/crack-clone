package com.crack.global.config

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * `crack.data-path` 설정값. 경로는 직접 조립하지 말고 [DataPaths]를 쓴다.
 * 상대 경로는 백엔드 프로세스의 작업 디렉터리 기준이다(기본 `./data`).
 */
@ConfigurationProperties(prefix = "crack")
data class DataPathConfig(
    val dataPath: String = "./data"
)
