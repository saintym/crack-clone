import { useRef, useState } from 'react';

interface ChatInputProps {
  /** 응답을 받는 중이면 스피너를 보인다 */
  streaming: boolean;
  /** 스트리밍 중이거나 서버가 생성 중(409)이면 입력을 잠근다 */
  locked: boolean;
  /** 이어쓰기할 수 있으면 true (대화의 마지막이 AI 메시지) */
  canContinue: boolean;
  /**
   * 상황서술 모드면 `**…**`로 감싼 뒤 넘긴다.
   * @returns 유저 메시지가 저장되었으면 true. false면 입력창에 내용을 되돌린다
   */
  onSend: (message: string) => Promise<boolean>;
  /** 빈 입력창에서 Enter 또는 버튼으로 이어쓰기 */
  onContinue: () => void;
  /** 입력창 위에 보일 안내·오류 문구 */
  notice?: string | null;
  onDismissNotice?: () => void;
}

const autoResize = (el: HTMLTextAreaElement) => {
  el.style.height = 'auto';
  el.style.height = Math.min(el.scrollHeight, 120) + 'px';
};

/** 메시지 입력창과 상황서술 토글. 입력이 비어 있으면 Enter·버튼이 이어쓰기가 된다 */
export default function ChatInput({
  streaming, locked, canContinue, onSend, onContinue, notice, onDismissNotice,
}: ChatInputProps) {
  const [input, setInput] = useState('');
  const [narrationMode, setNarrationMode] = useState(false);
  const inputRef = useRef<HTMLTextAreaElement>(null);

  const empty = !input.trim();

  const submit = () => {
    if (locked) return;
    if (empty) {
      if (canContinue) onContinue();
      return;
    }

    const text = input.trim();
    const narration = narrationMode;
    setInput('');
    setNarrationMode(false);
    if (inputRef.current) inputRef.current.style.height = 'auto';
    onSend(narration ? `**${text}**` : text).then((saved) => {
      if (saved) return;
      // 유저 메시지가 저장되지 않았다(409 등). 입력한 내용을 되돌린다.
      if (inputRef.current?.value) return; // 그사이 새로 입력했으면 건드리지 않는다
      setInput(text);
      setNarrationMode(narration);
    });
  };

  const handleKeyDown = (e: React.KeyboardEvent) => {
    // 한글 조합 중 Enter는 무시한다 (조합 확정용 Enter가 빈 입력 이어쓰기로 새지 않게)
    if (e.nativeEvent.isComposing || e.keyCode === 229) return;
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault();
      submit();
    }
  };

  let placeholder = narrationMode ? '상황을 서술하세요...' : '메시지를 입력하세요...';
  if (locked) placeholder = streaming ? '응답을 받는 중...' : '응답을 생성하는 중입니다...';
  else if (canContinue && !narrationMode) placeholder = '메시지를 입력하세요 (빈 채로 Enter: 이어쓰기)';

  return (
    <div className="safe-bottom shrink-0 border-t border-border/50 bg-bg-secondary/80 backdrop-blur-md px-4 py-3">
      {notice && (
        <div className="flex items-start gap-2 mb-2 px-3 py-2 rounded-xl bg-surface border border-border/60 text-[13px] text-text-secondary">
          <span className="flex-1 break-words">{notice}</span>
          {onDismissNotice && (
            <button onClick={onDismissNotice} title="닫기" className="text-text-muted hover:text-text-primary shrink-0">
              <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                <path d="M18 6L6 18M6 6l12 12" />
              </svg>
            </button>
          )}
        </div>
      )}
      <div className="flex items-end gap-2">
        <button
          onClick={() => setNarrationMode(!narrationMode)}
          title="상황서술 모드"
          className={`w-10 h-10 flex items-center justify-center rounded-xl shrink-0 transition-all text-[15px] font-bold ${
            narrationMode
              ? 'bg-accent text-white'
              : 'bg-surface hover:bg-surface-hover text-text-muted'
          }`}
        >
          **
        </button>
        <textarea
          ref={inputRef}
          value={input}
          onChange={(e) => {
            setInput(e.target.value);
            autoResize(e.target);
          }}
          onKeyDown={handleKeyDown}
          placeholder={placeholder}
          rows={1}
          disabled={locked}
          className={`flex-1 px-4 py-2.5 bg-surface border rounded-2xl text-text-primary placeholder-text-muted resize-none focus:outline-none transition-all text-[15px] ${
            narrationMode
              ? 'border-accent/60 italic'
              : 'border-border/60 focus:border-accent/60'
          }`}
          style={{ maxHeight: '120px' }}
        />
        <button
          onClick={submit}
          disabled={locked || (empty && !canContinue)}
          title={empty ? '이어쓰기' : '전송'}
          className="w-10 h-10 flex items-center justify-center rounded-full bg-accent hover:bg-accent-hover disabled:opacity-30 text-white transition-all shrink-0"
        >
          {streaming ? (
            <div className="w-4 h-4 border-2 border-white border-t-transparent rounded-full animate-spin" />
          ) : empty ? (
            <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round">
              <path d="M13 17l5-5-5-5M6 17l5-5-5-5" />
            </svg>
          ) : (
            <svg width="18" height="18" viewBox="0 0 24 24" fill="currentColor">
              <path d="M2.01 21L23 12 2.01 3 2 10l15 2-15 2z" />
            </svg>
          )}
        </button>
      </div>
    </div>
  );
}
