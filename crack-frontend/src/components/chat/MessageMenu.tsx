interface MessageMenuProps {
  /** 마지막 메시지면 재생성·이어하기 버튼을 보인다 */
  isLast: boolean;
  open: boolean;
  onToggle: () => void;
  onClose: () => void;
  onRegenerate: () => void;
  onContinue: () => void;
  onBranch: () => void;
  onEdit: () => void;
  onDelete: () => void;
}

const iconButton =
  'w-7 h-7 flex items-center justify-center rounded-lg text-text-muted hover:text-text-secondary hover:bg-surface-hover transition-all';
const menuItem =
  'w-full text-left px-4 py-2.5 text-sm hover:bg-surface-hover transition-all flex items-center gap-2';

/** AI 메시지 아래 액션 줄: 재생성, 이어하기, 더보기 메뉴(분기·수정·삭제) */
export default function MessageMenu({
  isLast, open, onToggle, onClose, onRegenerate, onContinue, onBranch, onEdit, onDelete,
}: MessageMenuProps) {
  return (
    <div className="flex items-center gap-1 mt-1.5 pl-1">
      {/* Regenerate & Continue buttons — last assistant message only */}
      {isLast && (
        <>
          <button onClick={onRegenerate} title="재생성" className={iconButton}>
            <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
              <path d="M1 4v6h6M23 20v-6h-6" />
              <path d="M20.49 9A9 9 0 005.64 5.64L1 10m22 4l-4.64 4.36A9 9 0 013.51 15" />
            </svg>
          </button>
          <button onClick={onContinue} title="이어하기" className={iconButton}>
            <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
              <path d="M5 12h14M12 5l7 7-7 7" />
            </svg>
          </button>
        </>
      )}

      {/* Menu button */}
      <div className="relative">
        <button onClick={onToggle} className={iconButton}>
          <svg width="16" height="16" viewBox="0 0 24 24" fill="currentColor">
            <circle cx="12" cy="5" r="2" />
            <circle cx="12" cy="12" r="2" />
            <circle cx="12" cy="19" r="2" />
          </svg>
        </button>
        {open && (
          <>
            <div className="fixed inset-0 z-40" onClick={onClose} />
            <div className="absolute left-0 bottom-full mb-1 z-50 bg-bg-tertiary border border-border/60 rounded-xl shadow-xl overflow-hidden min-w-[120px]">
              <button onClick={onBranch} className={`${menuItem} text-text-secondary`}>
                <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                  <path d="M6 3v12M18 9a3 3 0 100-6 3 3 0 000 6zM6 21a3 3 0 100-6 3 3 0 000 6zM18 9a9 9 0 01-9 9" />
                </svg>
                분기
              </button>
              <button onClick={onEdit} className={`${menuItem} text-text-secondary`}>
                <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                  <path d="M11 4H4a2 2 0 00-2 2v14a2 2 0 002 2h14a2 2 0 002-2v-7" />
                  <path d="M18.5 2.5a2.121 2.121 0 013 3L12 15l-4 1 1-4 9.5-9.5z" />
                </svg>
                수정
              </button>
              <button onClick={onDelete} className={`${menuItem} text-danger`}>
                <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                  <path d="M3 6h18M8 6V4a2 2 0 012-2h4a2 2 0 012 2v2M19 6l-1 14a2 2 0 01-2 2H8a2 2 0 01-2-2L5 6" />
                </svg>
                삭제
              </button>
            </div>
          </>
        )}
      </div>
    </div>
  );
}
