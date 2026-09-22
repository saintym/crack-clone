-- V4 (T02): DB에 절대/상대 경로를 저장하지 않는다 (BUG-005 근본 수정, DESIGN.md §1, §3).
-- 경로는 crack.data-path + scenarios.name + stories.dir_name 으로 계산한다.

-- 1. 스토리 폴더 이름 컬럼 추가
ALTER TABLE stories ADD COLUMN dir_name VARCHAR(64);

-- 2. 기존 data_path의 마지막 경로 조각으로 채운다.
--    - '.../stories/{dir}' (끝의 구분자 허용, '/'와 '\' 모두 허용) → '{dir}'
--    - 그 밖의 경로(V3가 만든 옛 기본 스토리처럼 시나리오 폴더 자체를 가리키던 것) → '_legacy'
UPDATE stories
SET dir_name = COALESCE(
    substring(data_path FROM '[/\\]stories[/\\]([^/\\]+)[/\\]*$'),
    '_legacy'
);

-- 3. NOT NULL 설정 후 경로 컬럼 삭제
ALTER TABLE stories ALTER COLUMN dir_name SET NOT NULL;
ALTER TABLE stories DROP COLUMN data_path;
ALTER TABLE scenarios DROP COLUMN data_path;
