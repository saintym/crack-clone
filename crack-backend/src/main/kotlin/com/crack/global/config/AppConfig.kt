package com.crack.global.config

import com.crack.ai.config.ClaudeConfig
import com.crack.global.auth.AuthConfig
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration

@Configuration
@EnableConfigurationProperties(DataPathConfig::class, ClaudeConfig::class, AuthConfig::class)
class AppConfig
