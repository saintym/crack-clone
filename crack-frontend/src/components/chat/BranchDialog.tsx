import { useState } from 'react';

interface BranchDialogProps {
  /** 열릴 때 입력창에 채울 제목 */
  initialTitle: string;
  /** 분기 실패 문구 (예: 이전되지 않은 옛 스토리는 400) */
  error?: string;
  onCancel: () => void;
  onConfirm: (title: string) => void;
}

/**
 * 스토리 분기 하단 시트. 사용자가 메뉴에서 직접 연 경우에만 뜬다.
 * 열릴 때마다 새로 마운트되므로 제목은 매번 initialTitle에서 시작한다.
 */
export default function BranchDialog({ initialTitle, error, onCancel, onConfirm }: BranchDialogProps) {
  const [branchTitle, setBranchTitle] = useState(initialTitle);

  return (
    <div className="fixed inset-0 z-50 flex items-end justify-center bg-black/50 backdrop-blur-sm" onClick={onCancel}>
      <div
        className="w-full max-w-lg bg-bg-secondary rounded-t-3xl px-6 pt-5 pb-8 safe-bottom border-t border-border/50"
        onClick={(e) => e.stopPropagation()}
      >
        <div className="w-10 h-1 bg-border-light/50 rounded-full mx-auto mb-6" />
        <h2 className="text-lg font-semibold text-text-primary mb-2">스토리 분기</h2>
        <p className="text-sm text-text-muted mb-5">이 메시지까지의 내용으로 새로운 스토리를 만듭니다.</p>
        <input
          type="text"
          value={branchTitle}
          onChange={(e) => setBranchTitle(e.target.value)}
          placeholder="새 스토리 제목"
          autoFocus
          onKeyDown={(e) => e.key === 'Enter' && onConfirm(branchTitle)}
          className="w-full px-5 py-4 bg-surface border border-border rounded-2xl text-text-primary placeholder-text-muted focus:outline-none focus:border-accent/60 transition-all text-[15px]"
        />
        {error && <p className="text-sm text-danger mt-3">{error}</p>}
        <div className="flex gap-3 mt-5">
          <button
            onClick={onCancel}
            className="flex-1 py-3.5 bg-surface hover:bg-surface-hover text-text-secondary rounded-2xl font-medium transition-all text-[15px]"
          >
            취소
          </button>
          <button
            onClick={() => onConfirm(branchTitle)}
            disabled={!branchTitle.trim()}
            className="flex-1 py-3.5 bg-accent hover:bg-accent-hover disabled:opacity-30 text-white rounded-2xl font-semibold transition-all text-[15px]"
          >
            분기
          </button>
        </div>
      </div>
    </div>
  );
}
