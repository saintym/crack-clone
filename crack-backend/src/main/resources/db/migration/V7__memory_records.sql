-- T14: 기억 기록 이력과 스토리의 마지막 기록 턴 (DESIGN.md §3 V7, §7.2)
CREATE TABLE memory_records (
    id               BIGSERIAL PRIMARY KEY,
    story_id         BIGINT NOT NULL REFERENCES stories(id) ON DELETE CASCADE,
    from_turn        INT NOT NULL,
    to_turn          INT NOT NULL,
    reason           VARCHAR(20) NOT NULL,   -- AUTO | MANUAL
    status           VARCHAR(20) NOT NULL,   -- RUNNING | DONE | FAILED | REVERTED
    changed_files    TEXT,                   -- JSON 배열: 스토리 폴더 기준 상대 경로
    rerecorded_turns TEXT,                   -- JSON 배열: 이번에 재반영한 수정된 과거 턴
    error            TEXT,
    seen             BOOLEAN NOT NULL DEFAULT FALSE,
    created_at       TIMESTAMP DEFAULT NOW(),
    finished_at      TIMESTAMP
);
CREATE INDEX idx_memory_records_story ON memory_records(story_id, id);

ALTER TABLE stories ADD COLUMN recorded_through_turn INT NOT NULL DEFAULT 0;
