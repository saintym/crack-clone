-- 시나리오 메타데이터 (파일 기반 시나리오의 인덱스)
CREATE TABLE scenarios (
    id              BIGSERIAL PRIMARY KEY,
    name            VARCHAR(255) NOT NULL UNIQUE,
    title           VARCHAR(255) NOT NULL,
    data_path       VARCHAR(2048) NOT NULL,
    turn_count      INT DEFAULT 0,
    status          VARCHAR(20) DEFAULT 'ACTIVE',
    created_at      TIMESTAMP DEFAULT NOW(),
    updated_at      TIMESTAMP DEFAULT NOW()
);

-- 대화 메시지 아카이빙 (100턴 이상 원문 보관)
CREATE TABLE chat_messages (
    id              BIGSERIAL PRIMARY KEY,
    scenario_id     BIGINT REFERENCES scenarios(id) ON DELETE CASCADE,
    role            VARCHAR(20) NOT NULL,
    character_name  VARCHAR(255),
    content         TEXT NOT NULL,
    emotion         VARCHAR(100),
    turn_number     INT,
    created_at      TIMESTAMP DEFAULT NOW()
);

CREATE INDEX idx_chat_messages_scenario ON chat_messages(scenario_id);
CREATE INDEX idx_chat_messages_turn ON chat_messages(scenario_id, turn_number);
