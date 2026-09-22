-- T03: 대화 메시지와 재생성 후보를 DB로 옮기기 위한 저장소 (DESIGN.md §3)
CREATE TABLE story_messages (
    id               BIGSERIAL PRIMARY KEY,
    story_id         BIGINT NOT NULL REFERENCES stories(id) ON DELETE CASCADE,
    seq              INT NOT NULL,                          -- 스토리 내 순서, 0부터
    turn_no          INT NOT NULL,                          -- 0 = 프롤로그. 유저 메시지와 그 응답은 같은 턴
    role             VARCHAR(20) NOT NULL,                  -- USER | ASSISTANT
    kind             VARCHAR(20) NOT NULL DEFAULT 'NORMAL', -- NORMAL | PROLOGUE | CONTINUATION | COMMAND
    content          TEXT NOT NULL,                         -- 현재 보이는 내용 (ASSISTANT는 선택된 후보의 사본). 감정 태그 제거됨
    emotion          VARCHAR(100),                          -- ASSISTANT만. 화면에 노출하지 않음
    selected_variant INT,                                   -- ASSISTANT만. 0부터
    edited_at        TIMESTAMP,
    created_at       TIMESTAMP DEFAULT NOW(),
    UNIQUE (story_id, seq)
);
CREATE INDEX idx_story_messages_turn ON story_messages(story_id, turn_no);

CREATE TABLE message_variants (
    id            BIGSERIAL PRIMARY KEY,
    message_id    BIGINT NOT NULL REFERENCES story_messages(id) ON DELETE CASCADE,
    variant_index INT NOT NULL,
    content       TEXT NOT NULL,
    emotion       VARCHAR(100),
    instruction   TEXT,                                     -- 재생성 지시 (선택)
    created_at    TIMESTAMP DEFAULT NOW(),
    UNIQUE (message_id, variant_index)
);
