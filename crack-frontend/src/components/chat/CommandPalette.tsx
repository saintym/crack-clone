import type { CommandInfo } from '../../api/commands';
import { commandOptionId } from './useCommandList';

interface CommandPaletteProps {
  /** 걸러진 명령 (비어 있으면 그리지 않는다) */
  items: CommandInfo[];
  activeIndex: number;
  onPick: (command: CommandInfo) => void;
  onHover: (index: number) => void;
}

/**
 * 입력창 위에 뜨는 `/` 명령 자동완성 목록. 키보드 처리(방향키·Enter·Tab·Esc)는 입력창이 하고,
 * 여기서는 목록을 그리고 마우스 선택만 받는다. 입력창의 포커스를 뺏지 않도록 mousedown 기본 동작을 막는다.
 */
export default function CommandPalette({ items, activeIndex, onPick, onHover }: CommandPaletteProps) {
  if (items.length === 0) return null;

  return (
    <ul
      id="command-palette"
      role="listbox"
      aria-label="명령"
      className="absolute bottom-full inset-x-0 mb-2 max-h-60 overflow-y-auto rounded-2xl border border-border/60 bg-bg-tertiary/95 backdrop-blur-md py-1.5 shadow-lg z-10"
    >
      {items.map((c, i) => (
        <li
          key={`${c.type}:${c.name}`}
          id={commandOptionId(i)}
          role="option"
          aria-selected={i === activeIndex}
          onMouseDown={(e) => e.preventDefault()}
          onMouseEnter={() => onHover(i)}
          onClick={() => onPick(c)}
          className={`flex items-baseline gap-2 px-3.5 py-2 cursor-pointer ${
            i === activeIndex ? 'bg-surface-active' : ''
          }`}
        >
          <span className="shrink-0 text-[14px] font-medium text-text-primary">/{c.name}</span>
          {c.type === 'SYSTEM' && (
            <span className="shrink-0 px-1.5 rounded bg-accent-soft text-accent text-[10px] leading-4">시스템</span>
          )}
          <span className="min-w-0 truncate text-[12px] text-text-muted">{c.description}</span>
        </li>
      ))}
    </ul>
  );
}
