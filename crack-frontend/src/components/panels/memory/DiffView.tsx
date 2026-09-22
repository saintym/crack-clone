import { useMemo, useState } from 'react';
import { diffLines } from 'diff';

/** 바뀐 줄 앞뒤로 남겨 보여 줄 같은 줄 수 */
const CONTEXT = 2;

type Row =
  | { type: 'add' | 'del' | 'same'; text: string }
  | { type: 'fold'; id: number; lines: string[] };

function splitLines(value: string): string[] {
  const lines = value.split('\n');
  if (lines[lines.length - 1] === '') lines.pop();
  return lines;
}

/** 줄 diff를 행 목록으로. 길게 같은 구간은 접는다(앞뒤 CONTEXT줄만 남김) */
function buildRows(before: string, after: string): { rows: Row[]; added: number; removed: number } {
  const rows: Row[] = [];
  let added = 0;
  let removed = 0;
  let foldId = 0;
  const changes = diffLines(before, after);
  changes.forEach((change, i) => {
    const lines = splitLines(change.value);
    if (change.added || change.removed) {
      const type = change.added ? 'add' : 'del';
      if (change.added) added += lines.length; else removed += lines.length;
      lines.forEach((text) => rows.push({ type, text }));
      return;
    }
    const head = i === 0 ? 0 : CONTEXT;
    const tail = i === changes.length - 1 ? 0 : CONTEXT;
    if (lines.length <= head + tail + 1) {
      lines.forEach((text) => rows.push({ type: 'same', text }));
      return;
    }
    lines.slice(0, head).forEach((text) => rows.push({ type: 'same', text }));
    rows.push({ type: 'fold', id: foldId++, lines: lines.slice(head, lines.length - tail) });
    lines.slice(lines.length - tail).forEach((text) => rows.push({ type: 'same', text }));
  });
  return { rows, added, removed };
}

const ROW_CLASS = {
  add: 'bg-success/10 text-text-primary',
  del: 'bg-danger/10 text-text-secondary',
  same: 'text-text-muted',
} as const;

const ROW_MARK = { add: '+', del: '−', same: ' ' } as const;

interface DiffViewProps {
  /** 기록 직전 내용. 기록 전에 없던 파일이면 null */
  before: string | null;
  /** 지금 내용. 파일이 없으면 null */
  current: string | null;
}

/** 파일 하나의 줄 단위 변경(before → 현재). 같은 구간은 접어 두고 눌러서 펼친다 */
export default function DiffView({ before, current }: DiffViewProps) {
  const { rows, added, removed } = useMemo(() => buildRows(before ?? '', current ?? ''), [before, current]);
  const [opened, setOpened] = useState<Set<number>>(() => new Set());

  if (added === 0 && removed === 0) {
    return <p className="px-3 py-2 text-[12px] text-text-muted">변경 없음 (현재 내용이 기록 전과 같습니다)</p>;
  }

  return (
    <div>
      <div className="flex gap-2 px-3 py-1.5 text-[11px] border-b border-border/40">
        {before === null && <span className="text-accent">새 파일</span>}
        {current === null && <span className="text-danger">지금은 없는 파일</span>}
        <span className="text-success">+{added}</span>
        <span className="text-danger">−{removed}</span>
      </div>
      <div className="font-mono text-[12px] leading-[1.6]">
        {rows.map((row, i) => {
          if (row.type === 'fold') {
            if (opened.has(row.id)) {
              return row.lines.map((text, j) => (
                <DiffLine key={`${i}-${j}`} type="same" text={text} />
              ));
            }
            return (
              <button
                key={i}
                onClick={() => setOpened((prev) => new Set(prev).add(row.id))}
                className="w-full text-left px-3 py-1 text-[11px] text-text-muted bg-bg-primary/40 hover:bg-surface-hover"
              >
                ⋯ 같은 줄 {row.lines.length}개 펼치기
              </button>
            );
          }
          return <DiffLine key={i} type={row.type} text={row.text} />;
        })}
      </div>
    </div>
  );
}

function DiffLine({ type, text }: { type: 'add' | 'del' | 'same'; text: string }) {
  return (
    <div className={`flex px-2 ${ROW_CLASS[type]}`}>
      <span className="w-4 shrink-0 select-none text-text-muted">{ROW_MARK[type]}</span>
      <span className="flex-1 min-w-0 whitespace-pre-wrap break-words">{text || ' '}</span>
    </div>
  );
}
