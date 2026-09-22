package com.crack.migration.controller

import com.crack.migration.legacy.LegacyMigrationReport
import com.crack.migration.legacy.LegacyStoryMigrator
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/** 관리용 이전 API. 인증은 다른 `/api` 경로와 같다(`AuthFilter`). */
@RestController
@RequestMapping("/api/admin")
class MigrationController(
    private val migrator: LegacyStoryMigrator,
) {
    /** 옛 스토리를 v2 구조로 옮긴다. 여러 번 불러도 안전하다(이미 이전한 스토리는 SKIPPED). */
    @PostMapping("/migrate-legacy")
    fun migrateLegacy(): LegacyMigrationReport = migrator.migrateAll()
}
