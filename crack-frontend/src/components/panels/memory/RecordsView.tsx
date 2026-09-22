import { useEffect, useState } from 'react';
import { memoryApi } from '../../../api/memory';
import type { TriggerResult } from '../../../api/memory';
import { errorMessage } from '../../../api/chat';
import type { MemoryState } from './useMemoryStatus';
import RecordDetail from './RecordDetail';
import RevertControl from './RevertControl';
import { REASON_LABEL, STATUS_CLASS, STATUS_LABEL, changedFileLabel, formatTime, rangeLabel } from './format';

const TRIGGER_NOTICE: Record<TriggerResult, string> = {
  STARTED: '기록을 시작했습니다. 채팅은 계속하셔도 됩니다.',
  ALREADY_RUNNING: '이미 기록하는 중입니다.',
  NOTHING_TO_RECORD: '새로 기록할 턴이 없습니다.',
};

interface RecordsViewProps {
  storyId: number;
  memory: MemoryState;
}

/** 기록 이력: "지금 기록" 버튼, 회차 목록(범위·상태·시각), 항목을 열면 변경 diff, 가장 최근 DONE만 되돌리기 */
export default function RecordsView({ storyId, memory }: RecordsViewProps) {
  const [selectedId, setSelectedId] = useState<number | null>(null);
  const [recording, setRecording] = useState(false);
  const [notice, setNotice] = useState<{ text: string; error?: boolean } | null>(null);
  const { refresh } = memory;

  // 패널을 열 때마다 목록을 새로 받는다
  useEffect(() => {
    refresh();
  }, [refresh]);

  const records = memory.records;
  const running = memory.status.status === 'RUNNING';
  const reloadKey = `${memory.status.lastRecordId}:${memory.status.status}`;

  const recordNow = () => {
    setRecording(true);
    setNotice(null);
    memoryApi.record(storyId)
      .then(({ data }) => {
        setNotice({ text: TRIGGER_NOTICE[data.result] });
        return refresh();
      })
      .catch((err) => setNotice({ text: errorMessage(err, '기록을 시작하지 못했습니다'), error: true }))
      .finally(() => setRecording(false));
  };

  if (selectedId !== null) {
    return (
      <RecordDetail
        storyId={storyId}
        recordId={selectedId}
        latestRecordId={memory.status.lastRecordId}
        reloadKey={reloadKey}
        onBack={() => setSelectedId(null)}
        onReverted={() => { refresh(); }}
      />
    );
  }

  return (
    <div className="p-3">
      <div className="flex items-center justify-between gap-2">
        <p className="text-[12px] text-text-muted">10턴마다 자동으로 기록합니다.</p>
        <button
          onClick={recordNow}
          disabled={recording || running}
          className="flex items-center gap-1.5 px-3 py-1.5 rounded-full bg-accent-soft text-accent text-[13px] font-medium hover:bg-accent/20 disabled:opacity-50"
        >
          {running && <span className="w-3 h-3 rounded-full border-2 border-accent/30 border-t-accent animate-spin" />}
          {running ? '기록 중' : '지금 기록'}
        </button>
      </div>
      {notice && (
        <p className={`mt-2 text-[12px] ${notice.error ? 'text-danger' : 'text-text-secondary'}`}>{notice.text}</p>
      )}

      <div className="mt-3 space-y-2">
        {records === null && <p className="text-[13px] text-text-muted">불러오는 중…</p>}
        {records?.length === 0 && <p className="text-[13px] text-text-muted">아직 기록이 없습니다.</p>}
        {records?.map((r) => (
          <div
            key={r.id}
            role="button"
            tabIndex={0}
            onClick={() => setSelectedId(r.id)}
            onKeyDown={(e) => { if (e.key === 'Enter' && e.target === e.currentTarget) setSelectedId(r.id); }}
            className="rounded-xl border border-border/50 bg-bg-primary/30 px-3 py-2.5 cursor-pointer hover:bg-surface-hover"
          >
            <div className="flex items-center gap-2 min-w-0">
              <span className={`shrink-0 px-2 py-0.5 rounded-full text-[11px] ${STATUS_CLASS[r.status]}`}>
                {STATUS_LABEL[r.status]}
              </span>
              <span className="text-[13px] text-text-primary truncate">{rangeLabel(r)}</span>
            </div>
            <p className="mt-1 text-[11px] text-text-muted truncate">
              {REASON_LABEL[r.reason]} · {formatTime(r.createdAt)}
              {r.changedFiles.length > 0 && ` · ${r.changedFiles.map(changedFileLabel).join(', ')}`}
            </p>
            {r.status === 'FAILED' && r.error && (
              <p className="mt-1 text-[11px] text-danger line-clamp-2 break-words">{r.error}</p>
            )}
            {r.revertable && (
              <div className="mt-2 flex justify-end">
                <RevertControl storyId={storyId} recordId={r.id} onReverted={() => { refresh(); }} />
              </div>
            )}
          </div>
        ))}
      </div>
    </div>
  );
}
