package com.crack.migration.legacy

import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component

/**
 * `crack.migration.legacy.enabled=true`일 때 기동 직후 옛 스토리 이전을 한 번 실행한다.
 * 이미 이전한 스토리는 건너뛰므로 켜 둔 채 재기동해도 안전하지만, 끝나면 끄는 것을 권장한다.
 * 실패한 스토리가 있어도 서버 기동은 계속한다(결과는 로그에 남는다).
 */
@Component
@ConditionalOnProperty(prefix = "crack.migration.legacy", name = ["enabled"], havingValue = "true")
class LegacyMigrationRunner(
    private val migrator: LegacyStoryMigrator,
) : ApplicationRunner {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun run(args: ApplicationArguments) {
        log.info("crack.migration.legacy.enabled=true: 옛 스토리 이전을 시작한다")
        val report = migrator.migrateAll()
        if (report.failed > 0) {
            log.warn("옛 스토리 이전 중 {}개가 실패했다. 원인을 고친 뒤 다시 실행하면 이어서 한다", report.failed)
        }
    }
}
