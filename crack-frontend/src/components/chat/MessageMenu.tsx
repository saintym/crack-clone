import { useState } from 'react';

interface MessageMenuProps {
  /** 유저 메시지면 오른쪽에 붙인다 */
  isUser: boolean;
  /** 가장 최근 AI 메시지의 후보 정보. 후보가 2개 이상일 때만 `‹ n/m ›`를 보인다 */
  variants?: { index: number; count: number; onSelect: (index: number) => void };
  /** 대화의 마지막 AI 메시지(프롤로그 제외)면 재생성과 "지시하고 재생성"을 보인다 */
  canRegenerate: boolean;
  /** 대화의 마지막 AI 메시지면 이어쓰기 버튼을 보인다 */
  canContinue: boolean;
  /** 대화의 마지막이 응답 없는 유저 메시지면(전송 실패) 응답 받기 버튼을 보인다 */
  canRetry: boolean;
  open: boolean;
  onToggle: () => void;
  onClose: () => void;
  onRegenerate: () => void;
  onRegenerateWithInstruction: () => void;
  onContinue: () => void;
  onBranch: () => void;
  onEdit: () => void;
  onDelete: () => void;
}

const iconButton =
  'w-7 h-7 flex items-center justify-center rounded-lg text-text-muted hover:text-text-secondary hover:bg-surface-hover transition-all disabled:opacity-30 disabled:hover:bg-transparent';
const menuItem =
  'w-full text-left px-4 py-2.5 text-sm hover:bg-surface-hover transition-all flex items-center gap-2 whitespace-nowrap';

/**
 * 메시지 아래 액션 줄: 후보 넘기기(‹ n/m ›), 재생성, 이어쓰기, 더보기 메뉴(지시하고 재생성·분기·수정·삭제).
 * 삭제는 메뉴 안에서 한 번 더 확인한다("이 메시지부터 끝까지 삭제됩니다").
 */
export default function MessageMenu({
  isUser, variants, canRegenerate, canContinue, canRetry, open, onToggle, onClose,
  onRegenerate, onRegenerateWithInstruction, onContinue, onBranch, onEdit, onDelete,
}: MessageMenuProps) {
  const [confirmingDelete, setConfirmingDelete] = useState(false);

  const close = () => {
    setConfirmingDelete(false);
    onClose();
  };
  const choose = (action: () => void) => () => {
    close();
    action();
  };

  return (
    <div className={`flex items-center gap-1 ${isUser ? 'flex-row-reverse' : ''}`}>
      {variants && variants.count >= 2 && (
        <div className="flex items-center text-[12px] text-text-muted tabular-nums select-none">
          <button
            onClick={() => variants.onSelect(variants.index - 1)}
            disabled={variants.index <= 0}
            title="이전 후보"
            className={iconButton}
          >
            <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round">
              <path d="M15 18l-6-6 6-6" />
            </svg>
          </button>
          <span className="px-0.5">{variants.index + 1}/{variants.count}</span>
          <button
            onClick={() => variants.onSelect(variants.index + 1)}
            disabled={variants.index >= variants.count - 1}
            title="다음 후보"
            className={iconButton}
          >
            <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round">
              <path d="M9 18l6-6-6-6" />
            </svg>
          </button>
        </div>
      )}

      {canRegenerate && (
        <button onClick={onRegenerate} title="재생성" className={iconButton}>
          <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
            <path d="M1 4v6h6M23 20v-6h-6" />
            <path d="M20.49 9A9 9 0 005.64 5.64L1 10m22 4l-4.64 4.36A9 9 0 013.51 15" />
          </svg>
        </button>
      )}
      {canContinue && (
        <button onClick={onContinue} title="이어쓰기 (빈 입력창에서 Enter)" className={iconButton}>
          <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
            <path d="M5 12h14M12 5l7 7-7 7" />
          </svg>
        </button>
      )}
      {canRetry && (
        <button
          onClick={onRegenerate}
          className="px-2.5 h-7 flex items-center gap-1 rounded-lg text-[12px] text-accent hover:bg-accent-soft transition-all"
        >
          <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
            <path d="M1 4v6h6" />
            <path d="M3.51 15A9 9 0 105.64 5.64L1 10" />
          </svg>
          응답 받기
        </button>
      )}

      <div className="relative">
        <button onClick={open ? close : onToggle} title="더보기" className={iconButton}>
          <svg width="16" height="16" viewBox="0 0 24 24" fill="currentColor">
            <circle cx="12" cy="5" r="2" />
            <circle cx="12" cy="12" r="2" />
            <circle cx="12" cy="19" r="2" />
          </svg>
        </button>
        {open && (
          <>
            <div className="fixed inset-0 z-40" onClick={close} />
            <div
              className={`absolute ${isUser ? 'right-0' : 'left-0'} bottom-full mb-1 z-50 bg-bg-tertiary border border-border/60 rounded-xl shadow-xl overflow-hidden min-w-[140px]`}
            >
              {confirmingDelete ? (
                <div className="px-4 py-3 w-56">
                  <p className="text-sm text-text-primary mb-1">이 메시지부터 끝까지 삭제됩니다.</p>
                  <p className="text-[12px] text-text-muted mb-3">되돌릴 수 없습니다.</p>
                  <div className="flex gap-2">
                    <button
                      onClick={close}
                      className="flex-1 py-1.5 text-sm bg-surface hover:bg-surface-hover text-text-secondary rounded-lg transition-all"
                    >
                      취소
                    </button>
                    <button
                      onClick={choose(onDelete)}
                      className="flex-1 py-1.5 text-sm bg-danger/90 hover:bg-danger text-white rounded-lg font-medium transition-all"
                    >
                      삭제
                    </button>
                  </div>
                </div>
              ) : (
                <>
                  {canRegenerate && (
                    <button onClick={choose(onRegenerateWithInstruction)} className={`${menuItem} text-text-secondary`}>
                      <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                        <path d="M21 15a2 2 0 01-2 2H7l-4 4V5a2 2 0 012-2h14a2 2 0 012 2z" />
                      </svg>
                      지시하고 재생성
                    </button>
                  )}
                  <button onClick={choose(onBranch)} className={`${menuItem} text-text-secondary`}>
                    <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                      <path d="M6 3v12M18 9a3 3 0 100-6 3 3 0 000 6zM6 21a3 3 0 100-6 3 3 0 000 6zM18 9a9 9 0 01-9 9" />
                    </svg>
                    분기
                  </button>
                  <button onClick={choose(onEdit)} className={`${menuItem} text-text-secondary`}>
                    <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                      <path d="M11 4H4a2 2 0 00-2 2v14a2 2 0 002 2h14a2 2 0 002-2v-7" />
                      <path d="M18.5 2.5a2.121 2.121 0 013 3L12 15l-4 1 1-4 9.5-9.5z" />
                    </svg>
                    수정
                  </button>
                  <button onClick={() => setConfirmingDelete(true)} className={`${menuItem} text-danger`}>
                    <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                      <path d="M3 6h18M8 6V4a2 2 0 012-2h4a2 2 0 012 2v2M19 6l-1 14a2 2 0 01-2 2H8a2 2 0 01-2-2L5 6" />
                    </svg>
                    삭제
                  </button>
                </>
              )}
            </div>
          </>
        )}
      </div>
    </div>
  );
}
