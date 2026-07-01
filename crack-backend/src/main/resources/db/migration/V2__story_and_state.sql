-- 스토리 요약 (다단계: L1=10턴, L2=30턴, L3=100턴)
CREATE TABLE story_summaries (
    id              BIGSERIAL PRIMARY KEY,
    scenario_id     BIGINT REFERENCES scenarios(id) ON DELETE CASCADE,
    level           VARCHAR(5) NOT NULL,
    from_turn       INT NOT NULL,
    to_turn         INT NOT NULL,
    content         TEXT NOT NULL,
    created_at      TIMESTAMP DEFAULT NOW()
);

CREATE INDEX idx_story_summaries_scenario ON story_summaries(scenario_id, level);

-- 캐릭터 이벤트 (일어난 일)
CREATE TABLE character_events (
    id              BIGSERIAL PRIMARY KEY,
    scenario_id     BIGINT REFERENCES scenarios(id) ON DELETE CASCADE,
    character_name  VARCHAR(255) NOT NULL,
    turn_number     INT NOT NULL,
    event_type      VARCHAR(30) NOT NULL,
    summary         VARCHAR(500) NOT NULL,
    detail          TEXT,
    created_at      TIMESTAMP DEFAULT NOW()
);

CREATE INDEX idx_char_events_scenario ON character_events(scenario_id, character_name);
CREATE INDEX idx_char_events_turn ON character_events(scenario_id, turn_number);

-- 캐릭터 상태 (현재 상태)
CREATE TABLE character_states (
    id              BIGSERIAL PRIMARY KEY,
    scenario_id     BIGINT REFERENCES scenarios(id) ON DELETE CASCADE,
    character_name  VARCHAR(255) NOT NULL,
    state_type      VARCHAR(30) NOT NULL,
    state_key       VARCHAR(255) NOT NULL,
    state_value     TEXT NOT NULL,
    context         TEXT,
    acquired_turn   INT,
    is_active       BOOLEAN DEFAULT TRUE,
    updated_at      TIMESTAMP DEFAULT NOW()
);

CREATE INDEX idx_char_states_scenario ON character_states(scenario_id, character_name);
CREATE INDEX idx_char_states_location ON character_states(scenario_id, state_type, is_active);

-- 시나리오 설정
CREATE TABLE scenario_settings (
    id              BIGSERIAL PRIMARY KEY,
    scenario_id     BIGINT REFERENCES scenarios(id) ON DELETE CASCADE,
    setting_key     VARCHAR(50) NOT NULL,
    setting_value   TEXT NOT NULL,
    created_at      TIMESTAMP DEFAULT NOW(),
    updated_at      TIMESTAMP DEFAULT NOW(),
    UNIQUE(scenario_id, setting_key)
);
