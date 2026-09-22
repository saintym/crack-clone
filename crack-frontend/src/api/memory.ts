import api from './client';

/** 기록 상태 (DESIGN.md §3 V7) */
export type MemoryRecordStatus = 'RUNNING' | 'DONE' | 'FAILED' | 'REVERTED';

/** 기록을 시작한 이유. AUTO = 10턴 자동, MANUAL = "지금 기록"·`/기록` */
export type MemoryRecordReason = 'AUTO' | 'MANUAL';

/**
 * `GET /messages`의 `story.memory` (DESIGN.md §7.2). 채팅 화면의 기억 뱃지가 쓴다.
 * - `status`: 가장 최근 기록의 상태. 기록이 없으면 `NONE`
 * - `unseen`: 읽음 처리되지 않은 DONE 또는 FAILED 기록이 있으면 true
 */
export interface MemoryStatus {
  status: MemoryRecordStatus | 'NONE';
  lastRecordId: number | null;
  unseen: boolean;
}

/**
 * 기록 목록 항목 (`MemoryRecordSummary`).
 * 새 턴 없이 고친 턴만 다시 반영한 기록은 `toTurn < fromTurn`(빈 범위)이다.
 * 시각은 서버 로컬 시각(`LocalDateTime`, 시간대 없음)이다.
 */
export interface MemoryRecordSummary {
  id: number;
  fromTurn: number;
  toTurn: number;
  reason: MemoryRecordReason;
  status: MemoryRecordStatus;
  changedFiles: string[];
  rerecordedTurns: number[];
  error: string | null;
  createdAt: string;
  finishedAt: string | null;
  /** 가장 최근 DONE이고 실행 중인 기록이 없을 때 true. 되돌리기 버튼은 이것으로만 판단한다 */
  revertable: boolean;
}

/** 변경 파일 하나. `before`는 기록 직전(기록 전에 없던 파일이면 null), `current`는 지금 내용(없으면 null) */
export interface MemoryRecordFile {
  path: string;
  before: string | null;
  current: string | null;
}

/** `GET /memory/records/{id}` */
export interface MemoryRecordDetail extends MemoryRecordSummary {
  files: MemoryRecordFile[];
}

export type TriggerResult = 'STARTED' | 'ALREADY_RUNNING' | 'NOTHING_TO_RECORD';

/** `POST /memory/record` 응답. `record`는 시작했거나 실행 중인 기록(없으면 null) */
export interface TriggerResponse {
  result: TriggerResult;
  record: MemoryRecordSummary | null;
}

const base = (storyId: number) => `/stories/${storyId}/memory`;

/** 기억 기록 API (DESIGN.md §7.2). 모든 경로는 `/api/stories/{storyId}/memory` 아래 */
export const memoryApi = {
  /** 수동 기록. 결과를 기다리지 않고 바로 돌아온다(항상 200) */
  record: (storyId: number) =>
    api.post<TriggerResponse>(`${base(storyId)}/record`),

  /** 기록 목록 (최근 것부터) */
  records: (storyId: number) =>
    api.get<MemoryRecordSummary[]>(`${base(storyId)}/records`),

  /** 기록 하나와 변경 파일별 before·current. diff는 프론트가 계산한다 */
  detail: (storyId: number, recordId: number) =>
    api.get<MemoryRecordDetail>(`${base(storyId)}/records/${recordId}`),

  /** 되돌리기. 가장 최근 DONE이 아니면 400, 기록이 실행 중이면 409 */
  revert: (storyId: number, recordId: number) =>
    api.post<MemoryRecordSummary>(`${base(storyId)}/records/${recordId}/revert`),

  /** 뱃지 읽음 처리 (이 스토리의 기록 전부). 204 */
  markSeen: (storyId: number) =>
    api.post<void>(`${base(storyId)}/records/seen`),
};
