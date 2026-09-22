import { lazy, Suspense } from 'react';
import type { MemoryStatus } from '../../../api/memory';

// 패널을 처음 열 때 받는다. 채팅 화면 첫 로딩을 늘리지 않으려는 것이다 (T15 기억 패널과 같은 방식)
const StatusPanel = lazy(() => import('./StatusPanel'));

/** StatusPanel을 지연 로딩한다 */
export default function StatusPanelLoader({ storyId, memory }: { storyId: number; memory: MemoryStatus }) {
  return (
    <Suspense fallback={<p className="p-3 text-[13px] text-text-muted">불러오는 중…</p>}>
      <StatusPanel storyId={storyId} memory={memory} />
    </Suspense>
  );
}
