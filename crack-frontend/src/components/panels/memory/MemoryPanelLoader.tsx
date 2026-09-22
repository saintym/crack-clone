import { lazy, Suspense } from 'react';
import type { MemoryState } from './useMemoryStatus';

// 패널을 처음 열 때 받는다(diff 라이브러리 포함). 채팅 화면 첫 로딩을 늘리지 않으려는 것이다
const MemoryPanel = lazy(() => import('./MemoryPanel'));

/** MemoryPanel을 지연 로딩한다 */
export default function MemoryPanelLoader({ storyId, memory }: { storyId: number; memory: MemoryState }) {
  return (
    <Suspense fallback={<p className="p-3 text-[13px] text-text-muted">불러오는 중…</p>}>
      <MemoryPanel storyId={storyId} memory={memory} />
    </Suspense>
  );
}
