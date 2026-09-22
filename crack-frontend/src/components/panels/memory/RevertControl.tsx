import { useState } from 'react';
import { memoryApi } from '../../../api/memory';
import { errorMessage } from '../../../api/chat';

interface RevertControlProps {
  storyId: number;
  recordId: number;
  /** 되돌린 뒤(성공) 목록·상태를 다시 받는다 */
  onReverted: () => void;
}

/**
 * 되돌리기 버튼과 인라인 확인 (모달 금지, D7). 가장 최근 DONE(`revertable`)에만 둔다.
 * 실패하면(400 최근 기록 아님, 409 기록 중) 서버 문구를 그 자리에 보인다.
 */
export default function RevertControl({ storyId, recordId, onReverted }: RevertControlProps) {
  const [confirming, setConfirming] = useState(false);
  const [pending, setPending] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const revert = () => {
    setPending(true);
    setError(null);
    memoryApi.revert(storyId, recordId)
      .then(() => {
        setConfirming(false);
        onReverted();
      })
      .catch((err) => setError(errorMessage(err, '되돌리지 못했습니다')))
      .finally(() => setPending(false));
  };

  if (!confirming) {
    return (
      <button
        onClick={(e) => { e.stopPropagation(); setConfirming(true); setError(null); }}
        className="px-2.5 py-1 rounded-full text-[12px] text-text-secondary border border-border hover:bg-surface-hover"
      >
        되돌리기
      </button>
    );
  }

  return (
    <div
      onClick={(e) => e.stopPropagation()}
      className="w-full rounded-xl border border-danger/30 bg-danger/5 px-3 py-2.5 text-[12px] cursor-default"
    >
      <p className="text-text-primary">
        이 기록이 바꾼 문서를 기록 전으로 되돌립니다. 이 범위의 턴은 다음 기록 때 다시 기록됩니다.
      </p>
      {error && <p className="mt-1.5 text-danger">{error}</p>}
      <div className="mt-2 flex justify-end gap-2">
        <button
          onClick={() => setConfirming(false)}
          disabled={pending}
          className="px-3 py-1 rounded-full text-text-secondary hover:bg-surface-hover"
        >
          취소
        </button>
        <button
          onClick={revert}
          disabled={pending}
          className="px-3 py-1 rounded-full bg-danger/90 text-white hover:bg-danger disabled:opacity-50"
        >
          {pending ? '되돌리는 중…' : '되돌리기'}
        </button>
      </div>
    </div>
  );
}
