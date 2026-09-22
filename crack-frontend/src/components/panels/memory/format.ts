import type { MemoryRecordReason, MemoryRecordStatus, MemoryRecordSummary } from '../../../api/memory';

export const STATUS_LABEL: Record<MemoryRecordStatus, string> = {
  RUNNING: '기록 중',
  DONE: '반영됨',
  FAILED: '실패',
  REVERTED: '되돌림',
};

export const STATUS_CLASS: Record<MemoryRecordStatus, string> = {
  RUNNING: 'bg-accent-soft text-accent',
  DONE: 'bg-success/15 text-success',
  FAILED: 'bg-danger/15 text-danger',
  REVERTED: 'bg-surface-active text-text-secondary',
};

export const REASON_LABEL: Record<MemoryRecordReason, string> = {
  AUTO: '자동',
  MANUAL: '수동',
};

/** 기록 범위. 새 턴 없이 고친 턴만 다시 반영한 기록은 `toTurn < fromTurn`이다 */
export function rangeLabel(r: Pick<MemoryRecordSummary, 'fromTurn' | 'toTurn' | 'rerecordedTurns'>): string {
  const range = r.toTurn < r.fromTurn
    ? '고친 턴 재반영'
    : r.fromTurn === r.toTurn ? `턴 ${r.fromTurn}` : `턴 ${r.fromTurn}–${r.toTurn}`;
  if (r.rerecordedTurns.length === 0) return range;
  const turns = r.rerecordedTurns.join(', ');
  return r.toTurn < r.fromTurn ? `${range} (턴 ${turns})` : `${range} · 재반영 턴 ${turns}`;
}

/** 서버 `LocalDateTime`(시간대 없음) → `9/23 14:03`. 시간대 없는 ISO 문자열은 브라우저 로컬 시각으로 읽힌다 */
export function formatTime(value: string | null): string {
  if (!value) return '';
  const d = new Date(value);
  if (Number.isNaN(d.getTime())) return value;
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${d.getMonth() + 1}/${d.getDate()} ${pad(d.getHours())}:${pad(d.getMinutes())}`;
}

/** 문서 경로를 보이는 이름으로. `characters/설월.md` → `설월` */
export function documentName(path: string): string {
  const file = path.split('/').pop() ?? path;
  return file.replace(/\.md$|\.json$/, '');
}

/** 기록이 바꾼 파일 경로의 보이는 이름 */
export function changedFileLabel(path: string): string {
  if (path === 'chronicle.md') return '연대기';
  if (path === 'state.json') return '상태';
  if (path === 'characters/protagonist.md') return '주인공';
  if (path.startsWith('characters/')) return documentName(path);
  return path;
}
