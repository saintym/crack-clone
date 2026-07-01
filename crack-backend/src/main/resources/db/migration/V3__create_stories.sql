-- 스토리 테이블 (시나리오의 플레이스루/인스턴스)
CREATE TABLE stories (
    id              BIGSERIAL PRIMARY KEY,
    scenario_id     BIGINT NOT NULL REFERENCES scenarios(id) ON DELETE CASCADE,
    title           VARCHAR(255) NOT NULL,
    data_path       VARCHAR(2048) NOT NULL,
    turn_count      INT DEFAULT 0,
    status          VARCHAR(20) DEFAULT 'ACTIVE',
    created_at      TIMESTAMP DEFAULT NOW(),
    updated_at      TIMESTAMP DEFAULT NOW()
);
CREATE INDEX idx_stories_scenario ON stories(scenario_id);

-- 기존 시나리오마다 기본 스토리 1개 생성
INSERT INTO stories (scenario_id, title, data_path, turn_count, status, created_at, updated_at)
SELECT id, '기본 스토리', data_path, turn_count, status, created_at, updated_at
FROM scenarios;

-- 기존 테이블에 story_id 추가
ALTER TABLE story_summaries ADD COLUMN story_id BIGINT REFERENCES stories(id) ON DELETE CASCADE;
ALTER TABLE character_events ADD COLUMN story_id BIGINT REFERENCES stories(id) ON DELETE CASCADE;
ALTER TABLE character_states ADD COLUMN story_id BIGINT REFERENCES stories(id) ON DELETE CASCADE;
ALTER TABLE chat_messages ADD COLUMN story_id BIGINT REFERENCES stories(id) ON DELETE CASCADE;

-- 기존 데이터의 story_id 채우기 (scenario_id 기반)
UPDATE story_summaries SET story_id = (SELECT s.id FROM stories s WHERE s.scenario_id = story_summaries.scenario_id LIMIT 1) WHERE story_id IS NULL;
UPDATE character_events SET story_id = (SELECT s.id FROM stories s WHERE s.scenario_id = character_events.scenario_id LIMIT 1) WHERE story_id IS NULL;
UPDATE character_states SET story_id = (SELECT s.id FROM stories s WHERE s.scenario_id = character_states.scenario_id LIMIT 1) WHERE story_id IS NULL;
UPDATE chat_messages SET story_id = (SELECT s.id FROM stories s WHERE s.scenario_id = chat_messages.scenario_id LIMIT 1) WHERE story_id IS NULL;

-- story_id NOT NULL 설정
ALTER TABLE story_summaries ALTER COLUMN story_id SET NOT NULL;
ALTER TABLE character_events ALTER COLUMN story_id SET NOT NULL;
ALTER TABLE character_states ALTER COLUMN story_id SET NOT NULL;
ALTER TABLE chat_messages ALTER COLUMN story_id SET NOT NULL;

-- story_id 인덱스
CREATE INDEX idx_story_summaries_story ON story_summaries(story_id, level);
CREATE INDEX idx_char_events_story ON character_events(story_id, character_name);
CREATE INDEX idx_char_states_story ON character_states(story_id, character_name);
CREATE INDEX idx_chat_messages_story ON chat_messages(story_id);

-- scenarios 에서 turn_count 제거 (이제 stories에서 관리)
ALTER TABLE scenarios DROP COLUMN turn_count;
