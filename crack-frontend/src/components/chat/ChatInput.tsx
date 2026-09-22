import { useEffect, useRef, useState } from 'react';
import { commandApi, findCommand, parseCommandInput, SYSTEM_COMMAND } from '../../api/commands';
import type { CommandInfo, SystemCommandResult } from '../../api/commands';
import { errorMessage } from '../../api/chat';
import CommandPalette from './CommandPalette';
import { commandOptionId, filterCommands, useCommandList } from './useCommandList';

interface ChatInputProps {
  storyId: number;
  /** 응답을 받는 중이면 스피너를 보인다 */
  streaming: boolean;
  /** 스트리밍 중이거나 서버가 생성 중(409)이면 입력을 잠근다 */
  locked: boolean;
  /** 이어쓰기할 수 있으면 true (대화의 마지막이 AI 메시지) */
  canContinue: boolean;
  /**
   * 상황서술 모드면 `**…**`로 감싼 뒤 넘긴다. 사용자 정의 명령이면 `command`에 이름을 준다(§8.2).
   * @returns 유저 메시지가 저장되었으면 true. false면 입력창에 내용을 되돌린다
   */
  onSend: (message: string, command?: string) => Promise<boolean>;
  /** 빈 입력창에서 Enter 또는 버튼으로 이어쓰기 */
  onContinue: () => void;
  /** `/ooc`만 입력했을 때: 지시 패널을 연다 */
  onOpenDirectives: () => void;
  /** 시스템 명령(`/기록`, `/ooc 내용`)이 실행된 뒤 (기억 뱃지·지시 목록 갱신) */
  onSystemCommand?: (result: SystemCommandResult) => void;
  /** 입력창 위에 보일 안내·오류 문구 */
  notice?: string | null;
  onDismissNotice?: () => void;
}

/** 입력창 위에 잠깐 떴다 사라지는 명령 결과 (모달 없음, D7) */
interface Toast {
  id: number;
  text: string;
  error?: boolean;
}

const TOAST_MS = 3500;

const autoResize = (el: HTMLTextAreaElement) => {
  el.style.height = 'auto';
  el.style.height = Math.min(el.scrollHeight, 120) + 'px';
};

/** 시스템 명령 결과를 한 줄로 */
function describeResult(result: SystemCommandResult): string {
  if (result.directive) {
    const text = result.directive.text;
    return `지시를 추가했습니다: ${text.length > 40 ? `${text.slice(0, 40)}…` : text}`;
  }
  switch (result.record?.result) {
    case 'STARTED': return '기억 기록을 시작했습니다';
    case 'ALREADY_RUNNING': return '이미 기억을 기록하는 중입니다';
    case 'NOTHING_TO_RECORD': return '새로 기록할 내용이 없습니다';
    default: return `/${result.name} 명령을 실행했습니다`;
  }
}

/**
 * 메시지 입력창과 상황서술 토글. 입력이 비어 있으면 Enter·버튼이 이어쓰기가 된다.
 * `/`로 시작하면 명령 자동완성을 띄우고(방향키·Enter·Tab 선택, Esc 닫기), 보낼 때 명령을 나눠 처리한다(§8.2).
 * - 시스템 명령은 REST로 실행하고 결과를 입력창 위 토스트로 알린다. `/ooc`만 입력하면 지시 패널을 연다
 * - 사용자 정의 명령은 `command`를 붙여 일반 전송과 같이 보낸다
 * - 상황서술 모드에서는 명령으로 읽지 않는다
 */
export default function ChatInput({
  storyId, streaming, locked, canContinue, onSend, onContinue, onOpenDirectives, onSystemCommand,
  notice, onDismissNotice,
}: ChatInputProps) {
  const [input, setInput] = useState('');
  const [narrationMode, setNarrationMode] = useState(false);
  const [activeIndex, setActiveIndex] = useState(0);
  const [paletteClosed, setPaletteClosed] = useState(false);
  const [running, setRunning] = useState(false);
  const [toast, setToast] = useState<Toast | null>(null);
  const inputRef = useRef<HTMLTextAreaElement>(null);
  const toastSeq = useRef(0);
  const commandList = useCommandList(storyId);

  const empty = !input.trim();

  // 이름을 입력하는 동안(`/` 뒤에 공백이 없을 때)만 자동완성을 띄운다
  const typingCommand = !narrationMode && !locked && !paletteClosed && /^\/\S*$/.test(input);
  const suggestions = typingCommand && commandList.commands ? filterCommands(commandList.commands, input.slice(1)) : [];
  const paletteOpen = suggestions.length > 0;
  const active = paletteOpen ? Math.min(activeIndex, suggestions.length - 1) : -1;

  useEffect(() => {
    if (!toast) return;
    const timer = setTimeout(() => setToast((t) => (t?.id === toast.id ? null : t)), TOAST_MS);
    return () => clearTimeout(timer);
  }, [toast]);

  const showToast = (text: string, error = false) => setToast({ id: ++toastSeq.current, text, error });

  const changeInput = (value: string) => {
    // `/`를 새로 입력하면 명령 목록을 다시 받는다 (그사이 commands.md가 바뀌었을 수 있다)
    if (value.startsWith('/') && !input.startsWith('/')) commandList.refresh().catch(() => {});
    setInput(value);
    setActiveIndex(0);
    setPaletteClosed(false);
  };

  const resetInput = () => {
    setInput('');
    setNarrationMode(false);
    if (inputRef.current) inputRef.current.style.height = 'auto';
  };

  /** 저장되지 않았거나 실행하지 못했으면 입력한 내용을 되돌린다. 그사이 새로 입력했으면 건드리지 않는다 */
  const restore = (text: string, narration = false) => {
    if (inputRef.current?.value) return;
    setInput(text);
    setNarrationMode(narration);
  };

  const pick = (command: CommandInfo) => {
    setInput(`/${command.name} `);
    setPaletteClosed(true);
    inputRef.current?.focus();
  };

  const runCommand = async (text: string, name: string, args: string) => {
    setRunning(true);
    try {
      let commands: CommandInfo[];
      try {
        commands = await commandList.ensure();
      } catch (err) {
        restore(text);
        showToast(errorMessage(err, '명령 목록을 불러오지 못했습니다'), true);
        return;
      }
      const command = findCommand(commands, name);
      if (!command) {
        restore(text);
        showToast(`없는 명령입니다: /${name}`, true);
        return;
      }
      if (command.type === 'CUSTOM') {
        const saved = await onSend(text, command.name);
        if (!saved) restore(text);
        return;
      }
      if (command.name.toLowerCase() === SYSTEM_COMMAND.OOC && !args) {
        onOpenDirectives();
        return;
      }
      try {
        const { data } = await commandApi.runSystem(storyId, command.name, args || undefined);
        showToast(describeResult(data));
        onSystemCommand?.(data);
      } catch (err) {
        restore(text);
        showToast(errorMessage(err, `/${command.name} 명령을 실행하지 못했습니다`), true);
      }
    } finally {
      setRunning(false);
    }
  };

  const submit = () => {
    if (locked || running) return;
    if (empty) {
      if (canContinue) onContinue();
      return;
    }

    const text = input.trim();
    const narration = narrationMode;
    resetInput();

    const parsed = narration ? null : parseCommandInput(text);
    if (parsed) {
      runCommand(text, parsed.name, parsed.args);
      return;
    }

    onSend(narration ? `**${text}**` : text).then((saved) => {
      // 유저 메시지가 저장되지 않았다(409 등). 입력한 내용을 되돌린다.
      if (!saved) restore(text, narration);
    });
  };

  const handleKeyDown = (e: React.KeyboardEvent) => {
    // 한글 조합 중 Enter는 무시한다 (조합 확정용 Enter가 빈 입력 이어쓰기로 새지 않게)
    if (e.nativeEvent.isComposing || e.keyCode === 229) return;

    if (paletteOpen) {
      const current = suggestions[active];
      if (e.key === 'ArrowDown' || e.key === 'ArrowUp') {
        e.preventDefault();
        const step = e.key === 'ArrowDown' ? 1 : -1;
        setActiveIndex((active + step + suggestions.length) % suggestions.length);
        return;
      }
      if (e.key === 'Escape') {
        e.preventDefault();
        setPaletteClosed(true);
        return;
      }
      if (e.key === 'Tab' || (e.key === 'Enter' && !e.shiftKey)) {
        // 이름을 다 입력한 상태의 Enter는 바로 실행한다 (`/기록` + Enter). 그 밖에는 이름을 채운다
        const typedFully = input.slice(1).toLowerCase() === current.name.toLowerCase();
        if (!(e.key === 'Enter' && typedFully)) {
          e.preventDefault();
          pick(current);
          return;
        }
      }
    }

    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault();
      submit();
    }
  };

  let placeholder = narrationMode ? '상황을 서술하세요...' : '메시지를 입력하세요 (/: 명령)';
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
      <div className="relative flex items-end gap-2">
        {toast && !paletteOpen && (
          <div
            role="status"
            className={`absolute bottom-full left-1/2 -translate-x-1/2 mb-2 max-w-[90%] px-3 py-1.5 rounded-full border text-[12px] shadow-md truncate pointer-events-none z-10 ${
              toast.error
                ? 'bg-bg-tertiary border-danger/40 text-danger'
                : 'bg-bg-tertiary border-border/60 text-text-secondary'
            }`}
          >
            {toast.text}
          </div>
        )}
        <CommandPalette
          items={suggestions}
          activeIndex={active}
          onPick={pick}
          onHover={setActiveIndex}
        />
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
            changeInput(e.target.value);
            autoResize(e.target);
          }}
          onKeyDown={handleKeyDown}
          onBlur={() => setPaletteClosed(true)}
          onFocus={() => setPaletteClosed(false)}
          placeholder={placeholder}
          rows={1}
          disabled={locked}
          role="combobox"
          aria-expanded={paletteOpen}
          aria-controls={paletteOpen ? 'command-palette' : undefined}
          aria-activedescendant={paletteOpen ? commandOptionId(active) : undefined}
          aria-autocomplete="list"
          className={`flex-1 px-4 py-2.5 bg-surface border rounded-2xl text-text-primary placeholder-text-muted resize-none focus:outline-none transition-all text-[15px] ${
            narrationMode
              ? 'border-accent/60 italic'
              : 'border-border/60 focus:border-accent/60'
          }`}
          style={{ maxHeight: '120px' }}
        />
        <button
          onClick={submit}
          disabled={locked || running || (empty && !canContinue)}
          title={empty ? '이어쓰기' : '전송'}
          className="w-10 h-10 flex items-center justify-center rounded-full bg-accent hover:bg-accent-hover disabled:opacity-30 text-white transition-all shrink-0"
        >
          {streaming || running ? (
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
