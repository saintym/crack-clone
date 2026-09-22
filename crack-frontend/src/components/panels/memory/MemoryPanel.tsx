import { useEffect, useState } from 'react';
import type { MemoryState } from './useMemoryStatus';
import DocumentsView from './DocumentsView';
import RecordsView from './RecordsView';

type View = 'documents' | 'records';

interface MemoryPanelProps {
  storyId: number;
  memory: MemoryState;
}

/**
 * 오른쪽 드로어의 "기억" 탭: 문서(보기·편집)와 기록 이력(diff·되돌리기·지금 기록).
 * 이 탭이 보이는 동안 끝난 기록은 읽은 것으로 처리해 뱃지를 지운다.
 */
export default function MemoryPanel({ storyId, memory }: MemoryPanelProps) {
  // 새 기록이 있으면 기록 이력부터 보여 준다
  const [view, setView] = useState<View>(() => (memory.status.unseen ? 'records' : 'documents'));
  const { unseen, status } = memory.status;
  const { markSeen } = memory;

  // 실행 중에 읽음 처리하면 서버가 실행 중인 기록까지 읽음으로 바꿔, 끝났을 때 뱃지가 뜨지 않는다. 끝난 뒤에 처리한다
  useEffect(() => {
    if (unseen && status !== 'RUNNING') markSeen();
  }, [unseen, status, markSeen]);

  const reloadKey = `${memory.status.lastRecordId}:${status}`;

  return (
    <div className="flex flex-col min-h-full">
      <div className="flex gap-1 px-3 pt-3">
        {([['documents', '문서'], ['records', '기록 이력']] as const).map(([id, label]) => (
          <button
            key={id}
            onClick={() => setView(id)}
            className={`px-3 py-1 rounded-lg text-[12px] transition-all ${
              view === id ? 'bg-surface-active text-text-primary font-medium' : 'text-text-muted hover:bg-surface-hover'
            }`}
          >
            {label}
            {id === 'records' && status === 'RUNNING' && (
              <span className="inline-block ml-1.5 w-2.5 h-2.5 rounded-full border-2 border-accent/30 border-t-accent animate-spin align-[-1px]" />
            )}
          </button>
        ))}
      </div>
      {view === 'documents'
        ? <DocumentsView storyId={storyId} reloadKey={reloadKey} />
        : <RecordsView storyId={storyId} memory={memory} />}
    </div>
  );
}
