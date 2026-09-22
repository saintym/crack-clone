import { useEffect, useState } from 'react';
import { memoryApi } from '../../../api/memory';
import type { MemoryRecordDetail } from '../../../api/memory';
import { errorMessage } from '../../../api/chat';
import DiffView from './DiffView';
import RevertControl from './RevertControl';
import { REASON_LABEL, STATUS_CLASS, STATUS_LABEL, changedFileLabel, formatTime, rangeLabel } from './format';

interface RecordDetailProps {
  storyId: number;
  recordId: number;
  /** 가장 최근 기록 ID. 이 기록이 아니면 "현재" 내용에 이후 기록의 변경도 섞여 있다 */
  latestRecordId: number | null;
  /** 바뀌면 다시 받는다 (기록 상태가 바뀌었을 때) */
  reloadKey: string;
  onBack: () => void;
  onReverted: () => void;
}

interface Loaded {
  key: string;
  detail: MemoryRecordDetail | null;
  error: string | null;
}

/** 기록 한 회의 변경 파일별 diff (기록 직전 → 현재) */
export default function RecordDetail({ storyId, recordId, latestRecordId, reloadKey, onBack, onReverted }: RecordDetailProps) {
  const key = `${storyId}:${recordId}:${reloadKey}`;
  const [loaded, setLoaded] = useState<Loaded | null>(null);

  useEffect(() => {
    let cancelled = false;
    memoryApi.detail(storyId, recordId)
      .then(({ data }) => { if (!cancelled) setLoaded({ key, detail: data, error: null }); })
      .catch((err) => { if (!cancelled) setLoaded({ key, detail: null, error: errorMessage(err, '기록을 불러오지 못했습니다') }); });
    return () => { cancelled = true; };
  }, [storyId, recordId, key]);

  // 다시 받는 동안에는 이전 내용을 그대로 보인다 (같은 기록일 때)
  const detail = loaded?.detail?.id === recordId ? loaded.detail : null;
  const error = loaded?.key === key ? loaded.error : null;

  return (
    <div className="p-3">
      <button onClick={onBack} className="flex items-center gap-1 text-[13px] text-text-secondary hover:text-text-primary mb-3">
        <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round">
          <path d="M15 18l-6-6 6-6" />
        </svg>
        기록 목록
      </button>

      {error && <p className="text-[13px] text-danger">{error}</p>}
      {!detail && !error && <p className="text-[13px] text-text-muted">불러오는 중…</p>}

      {detail && (
        <>
          <div className="flex items-center gap-2">
            <span className={`px-2 py-0.5 rounded-full text-[11px] ${STATUS_CLASS[detail.status]}`}>
              {STATUS_LABEL[detail.status]}
            </span>
            <span className="text-[14px] font-medium text-text-primary">{rangeLabel(detail)}</span>
          </div>
          <p className="mt-1 text-[12px] text-text-muted">
            {REASON_LABEL[detail.reason]} · {formatTime(detail.createdAt)}
            {detail.finishedAt && ` → ${formatTime(detail.finishedAt)}`}
          </p>
          {detail.revertable && (
            <div className="mt-2 flex justify-end">
              <RevertControl storyId={storyId} recordId={detail.id} onReverted={onReverted} />
            </div>
          )}

          {detail.error && (
            <p className="mt-2 rounded-lg bg-danger/10 px-3 py-2 text-[12px] text-danger whitespace-pre-wrap break-words">
              {detail.error}
            </p>
          )}

          {detail.files.length > 0 && (
            <p className="mt-3 text-[11px] text-text-muted">
              기록 직전 내용 → 현재 내용
              {latestRecordId !== null && detail.id !== latestRecordId && ' (이후 기록과 직접 고친 내용도 함께 보입니다)'}
            </p>
          )}
          {detail.files.length === 0 && detail.status !== 'RUNNING' && (
            <p className="mt-3 text-[13px] text-text-muted">바뀐 문서가 없습니다.</p>
          )}

          <div className="mt-2 space-y-3">
            {detail.files.map((file) => (
              <section key={file.path} className="rounded-xl border border-border/50 overflow-hidden bg-bg-primary/30">
                <header className="px-3 py-2 border-b border-border/40 flex items-baseline gap-2">
                  <span className="text-[13px] font-medium text-text-primary">{changedFileLabel(file.path)}</span>
                  <span className="text-[11px] text-text-muted truncate">{file.path}</span>
                </header>
                <DiffView before={file.before} current={file.current} />
              </section>
            ))}
          </div>
        </>
      )}
    </div>
  );
}
