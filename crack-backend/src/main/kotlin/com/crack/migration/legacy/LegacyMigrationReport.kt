package com.crack.migration.legacy

/** 스토리 하나의 이전 결과. */
enum class LegacyMigrationStatus {
    /** 이번 실행에서 이전했다 */
    MIGRATED,

    /** 이미 v2(`story.json` 있음)라 건너뛰었다 */
    SKIPPED,

    /** 실패했다. 원인은 [StoryMigrationResult.error]. 다시 실행하면 이어서 한다 */
    FAILED,
}

data class StoryMigrationResult(
    val storyId: Long,
    val scenarioName: String,
    val title: String,
    /** 이전 전 `dir_name`. `_legacy`면 새 폴더로 옮겼다는 뜻이다 */
    val fromDirName: String,
    /** 이전 후 `dir_name` */
    val dirName: String,
    val status: LegacyMigrationStatus,
    /** DB로 가져온 메시지 수 */
    val importedMessages: Int = 0,
    /** 가져온 메시지의 최대 턴 번호 */
    val turnCount: Int = 0,
    val warnings: List<String> = emptyList(),
    val error: String? = null,
)

data class LegacyMigrationReport(
    val migrated: Int,
    val skipped: Int,
    val failed: Int,
    val stories: List<StoryMigrationResult>,
) {
    companion object {
        fun of(stories: List<StoryMigrationResult>) = LegacyMigrationReport(
            migrated = stories.count { it.status == LegacyMigrationStatus.MIGRATED },
            skipped = stories.count { it.status == LegacyMigrationStatus.SKIPPED },
            failed = stories.count { it.status == LegacyMigrationStatus.FAILED },
            stories = stories,
        )
    }
}
