-- T27: 응답 첫 줄 인물 태그 `[인물: 이름/변형]` 저장 (DESIGN.md §3, §5.3, D31)
-- ASSISTANT 메시지만 값이 있고, 재생성 후보마다 따로 저장한다.

ALTER TABLE story_messages
    ADD COLUMN speaker         VARCHAR(100),
    ADD COLUMN speaker_variant VARCHAR(50);

ALTER TABLE message_variants
    ADD COLUMN speaker         VARCHAR(100),
    ADD COLUMN speaker_variant VARCHAR(50);
