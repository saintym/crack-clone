-- T38: 이야기 속 시각과 장소 (DESIGN.md §5.4, D39)
-- 응답 첫 줄 `[시간: 2026-09-28 23:40] [장소: 에미야 저택]` 태그에서 뽑은 값이다.
-- ASSISTANT 메시지만 값이 있고, 재생성 후보마다 따로 저장한다.
-- 시계를 쓰지 않는 스토리(옛 스토리, clock.enabled=false)는 NULL로 남는다.

ALTER TABLE story_messages
    ADD COLUMN story_time TIMESTAMP,
    ADD COLUMN place      VARCHAR(200);

ALTER TABLE message_variants
    ADD COLUMN story_time TIMESTAMP,
    ADD COLUMN place      VARCHAR(200);
