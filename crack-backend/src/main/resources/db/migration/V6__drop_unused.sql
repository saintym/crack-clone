-- V6 (T12): v2에서 쓰지 않는 테이블 삭제.
-- 옛 파일 기반 채팅(chat_messages), 요약(story_summaries), 캐릭터 상태·이벤트(character_events, character_states),
-- 시나리오 설정(scenario_settings). 엔티티와 코드는 같은 작업에서 지웠다.
DROP TABLE IF EXISTS chat_messages, story_summaries, character_events, character_states, scenario_settings;
