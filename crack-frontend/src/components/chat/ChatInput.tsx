import { useRef, useState } from 'react';

interface ChatInputProps {
  /** 응답을 받는 중이면 입력창을 잠그고 스피너를 보인다 */
  streaming: boolean;
  /** true면 전송하지 않는다 (입력 내용도 그대로 둔다) */
  sendBlocked: boolean;
  /** 상황서술 모드면 `**…**`로 감싼 뒤 넘긴다 */
  onSend: (message: string) => void;
}

const autoResize = (el: HTMLTextAreaElement) => {
  el.style.height = 'auto';
  el.style.height = Math.min(el.scrollHeight, 120) + 'px';
};

/** 메시지 입력창과 상황서술 토글 */
export default function ChatInput({ streaming, sendBlocked, onSend }: ChatInputProps) {
  const [input, setInput] = useState('');
  const [narrationMode, setNarrationMode] = useState(false);
  const inputRef = useRef<HTMLTextAreaElement>(null);

  const submit = () => {
    if (!input.trim() || sendBlocked) return;

    let userMsg = input.trim();
    if (narrationMode) {
      userMsg = `**${userMsg}**`;
    }
    setInput('');
    setNarrationMode(false);
    onSend(userMsg);
  };

  const handleKeyDown = (e: React.KeyboardEvent) => {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault();
      submit();
    }
  };

  return (
    <div className="safe-bottom shrink-0 border-t border-border/50 bg-bg-secondary/80 backdrop-blur-md px-4 py-3">
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
          placeholder={narrationMode ? '상황을 서술하세요...' : '메시지를 입력하세요...'}
          rows={1}
          disabled={streaming}
          className={`flex-1 px-4 py-2.5 bg-surface border rounded-2xl text-text-primary placeholder-text-muted resize-none focus:outline-none transition-all text-[15px] ${
            narrationMode
              ? 'border-accent/60 italic'
              : 'border-border/60 focus:border-accent/60'
          }`}
          style={{ maxHeight: '120px' }}
        />
        <button
          onClick={submit}
          disabled={!input.trim() || streaming}
          className="w-10 h-10 flex items-center justify-center rounded-full bg-accent hover:bg-accent-hover disabled:opacity-30 text-white transition-all shrink-0"
        >
          {streaming ? (
            <div className="w-4 h-4 border-2 border-white border-t-transparent rounded-full animate-spin" />
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
