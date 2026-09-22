import type { MemoryStatus } from '../../../api/memory';

/**
 * 채팅 헤더의 기억 뱃지 (D7: 흐름을 끊지 않는 작은 표시).
 * 기록 중이면 작은 스피너, 읽지 않은 새 기록이 있으면 점 하나(실패면 붉은 점). 그 밖에는 아무것도 그리지 않는다.
 */
export default function MemoryBadge({ status }: { status: MemoryStatus }) {
  if (status.status === 'RUNNING') {
    return (
      <span
        title="기억을 기록하는 중"
        aria-label="기억을 기록하는 중"
        className="block w-2.5 h-2.5 rounded-full border-2 border-accent/30 border-t-accent animate-spin"
      />
    );
  }
  if (status.unseen) {
    const failed = status.status === 'FAILED';
    return (
      <span
        title={failed ? '기억 기록에 실패했습니다' : '새 기억이 기록되었습니다'}
        aria-label={failed ? '기억 기록 실패' : '새 기억 기록'}
        className={`block w-2 h-2 rounded-full ${failed ? 'bg-danger' : 'bg-accent'}`}
      />
    );
  }
  return null;
}
