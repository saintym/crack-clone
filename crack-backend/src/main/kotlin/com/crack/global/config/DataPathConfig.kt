package com.crack.global.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "crack")
data class DataPathConfig(
    val dataPath: String = "./data"
)
