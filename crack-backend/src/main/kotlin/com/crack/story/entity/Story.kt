package com.crack.story.entity

import jakarta.persistence.*
import java.time.LocalDateTime

@Entity
@Table(name = "stories")
class Story(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(name = "scenario_id", nullable = false)
    val scenarioId: Long,

    @Column(nullable = false)
    var title: String,

    /** 스토리 폴더 이름. 경로는 DataPaths.storyDir(scenario.name, dirName)으로 계산한다. `_legacy`는 시나리오 폴더 자체. */
    @Column(name = "dir_name", nullable = false, length = 64)
    val dirName: String,

    @Column(name = "turn_count")
    var turnCount: Int = 0,

    @Column
    @Enumerated(EnumType.STRING)
    var status: StoryStatus = StoryStatus.ACTIVE,

    @Column(name = "created_at")
    val createdAt: LocalDateTime = LocalDateTime.now(),

    @Column(name = "updated_at")
    var updatedAt: LocalDateTime = LocalDateTime.now(),

    /** 기억 기록이 반영된 마지막 턴 (V7, T14). 이 턴까지는 기억 문서에 들어 있다. 0이면 아직 기록 없음. */
    @Column(name = "recorded_through_turn", nullable = false)
    var recordedThroughTurn: Int = 0,
)

enum class StoryStatus {
    ACTIVE, ARCHIVED
}
