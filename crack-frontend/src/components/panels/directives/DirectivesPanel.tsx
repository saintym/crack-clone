import { useState } from 'react';
import type { Directive } from '../../../api/directives';
import type { DirectivesState } from './useDirectives';

/** 한글 조합 중 Enter는 조합 확정이므로 무시한다 */
const isComposing = (e: React.KeyboardEvent) => e.nativeEvent.isComposing || e.keyCode === 229;

/**
 * 오른쪽 드로어의 "지시" 탭: 지속 OOC 지시(D10) 목록, 켜기·끄기, 수정, 삭제, 추가.
 * 켜진 지시는 해제할 때까지 매 턴 프롬프트 맨 아래에 들어간다. 확인은 모두 그 자리에서 한다(모달 없음, D7).
 */
export default function DirectivesPanel({ directives }: { directives: DirectivesState }) {
  const { list, error, enabledCount } = directives;

  return (
    <div className="p-3 space-y-3">
      <p className="px-1 text-[12px] leading-relaxed text-text-muted">
        켜진 지시는 끌 때까지 매 턴 프롬프트 맨 아래에 들어갑니다. 채팅창에서 <code className="font-mono">/ooc 내용</code>으로도 추가할 수 있습니다.
      </p>

      <AddDirective onAdd={directives.add} />

      {error && <p className="px-1 text-[13px] text-danger">{error}</p>}

      {list === null && !error && <p className="px-1 text-[13px] text-text-muted">불러오는 중…</p>}

      {list !== null && (
        <div>
          <h3 className="px-1 mb-1.5 text-[12px] font-medium text-text-muted">
            {list.length === 0 ? '지시가 없습니다.' : `지시 ${list.length}개 · 켜짐 ${enabledCount}개`}
          </h3>
          <ul className="space-y-1.5">
            {list.map((d) => (
              <DirectiveItem key={d.id} directive={d} directives={directives} />
            ))}
          </ul>
        </div>
      )}
    </div>
  );
}

function AddDirective({ onAdd }: { onAdd: (text: string) => Promise<string | null> }) {
  const [text, setText] = useState('');
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const submit = () => {
    const value = text.trim();
    if (!value || saving) return;
    setSaving(true);
    setError(null);
    onAdd(value).then((err) => {
      setSaving(false);
      if (err) setError(err);
      else setText('');
    });
  };

  return (
    <div className="rounded-xl border border-border/50 bg-bg-primary/30 p-2">
      <textarea
        value={text}
        onChange={(e) => setText(e.target.value)}
        onKeyDown={(e) => {
          if (isComposing(e)) return;
          if (e.key === 'Enter' && !e.shiftKey) {
            e.preventDefault();
            submit();
          }
        }}
        rows={2}
        placeholder="새 지시 (예: 말투는 반말)"
        className="w-full resize-none bg-transparent px-1 py-1 text-[14px] text-text-primary placeholder-text-muted focus:outline-none"
      />
      <div className="flex items-center justify-end gap-2">
        {error && <span className="flex-1 text-[12px] text-danger">{error}</span>}
        <button
          onClick={submit}
          disabled={!text.trim() || saving}
          className="px-3 py-1 rounded-lg bg-accent hover:bg-accent-hover disabled:opacity-30 text-white text-[12px] font-medium"
        >
          {saving ? '추가 중…' : '추가'}
        </button>
      </div>
    </div>
  );
}

type Mode = 'view' | 'edit' | 'confirmDelete';

function DirectiveItem({ directive, directives }: { directive: Directive; directives: DirectivesState }) {
  const [mode, setMode] = useState<Mode>('view');
  const [draft, setDraft] = useState(directive.text);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  /** 요청 하나를 보내고, 성공하면 보기로 돌아간다 */
  const run = (request: Promise<string | null>, onSuccess?: () => void) => {
    setBusy(true);
    setError(null);
    request.then((err) => {
      setBusy(false);
      if (err) setError(err);
      else onSuccess?.();
    });
  };

  const saveEdit = () => {
    const value = draft.trim();
    if (!value) return;
    if (value === directive.text) {
      setMode('view');
      return;
    }
    run(directives.update(directive.id, { text: value }), () => setMode('view'));
  };

  return (
    <li className={`rounded-xl border px-3 py-2.5 ${
      directive.enabled ? 'border-border/60 bg-bg-primary/30' : 'border-border/30 bg-transparent'
    }`}
    >
      <div className="flex items-start gap-2.5">
        <button
          role="switch"
          aria-checked={directive.enabled}
          title={directive.enabled ? '끄기' : '켜기'}
          disabled={busy}
          onClick={() => run(directives.update(directive.id, { enabled: !directive.enabled }))}
          className={`mt-0.5 relative w-8 h-[18px] shrink-0 rounded-full transition-colors disabled:opacity-50 ${
            directive.enabled ? 'bg-accent' : 'bg-surface-active'
          }`}
        >
          <span className={`absolute top-[2px] w-[14px] h-[14px] rounded-full bg-white transition-all ${
            directive.enabled ? 'left-[16px]' : 'left-[2px]'
          }`}
          />
        </button>

        {mode === 'edit' ? (
          <div className="flex-1 min-w-0">
            <textarea
              value={draft}
              onChange={(e) => setDraft(e.target.value)}
              onKeyDown={(e) => {
                if (isComposing(e)) return;
                if (e.key === 'Enter' && !e.shiftKey) {
                  e.preventDefault();
                  saveEdit();
                } else if (e.key === 'Escape') {
                  setMode('view');
                }
              }}
              rows={2}
              autoFocus
              className="w-full resize-none rounded-lg border border-accent/50 bg-surface px-2 py-1 text-[14px] text-text-primary focus:outline-none"
            />
            <div className="mt-1 flex justify-end gap-2">
              <button onClick={() => setMode('view')} className="px-2 py-0.5 text-[12px] text-text-muted hover:text-text-primary">
                취소
              </button>
              <button
                onClick={saveEdit}
                disabled={!draft.trim() || busy}
                className="px-2.5 py-0.5 rounded-md bg-accent hover:bg-accent-hover disabled:opacity-30 text-white text-[12px]"
              >
                저장
              </button>
            </div>
          </div>
        ) : (
          <p className={`flex-1 min-w-0 text-[14px] leading-relaxed whitespace-pre-wrap break-words ${
            directive.enabled ? 'text-text-primary' : 'text-text-muted line-through decoration-text-muted/50'
          }`}
          >
            {directive.text}
          </p>
        )}

        {mode === 'view' && (
          <div className="flex shrink-0 gap-0.5">
            <IconButton title="수정" onClick={() => { setDraft(directive.text); setError(null); setMode('edit'); }}>
              <path d="M12 20h9M16.5 3.5a2.1 2.1 0 013 3L7 19l-4 1 1-4 12.5-12.5z" />
            </IconButton>
            <IconButton title="삭제" danger onClick={() => { setError(null); setMode('confirmDelete'); }}>
              <path d="M3 6h18M8 6V4h8v2M19 6l-1 14H6L5 6" />
            </IconButton>
          </div>
        )}
      </div>

      {mode === 'confirmDelete' && (
        <div className="mt-2 flex items-center justify-end gap-2 text-[12px]">
          <span className="flex-1 text-text-secondary">이 지시를 삭제할까요?</span>
          <button onClick={() => setMode('view')} className="px-2 py-0.5 text-text-muted hover:text-text-primary">
            취소
          </button>
          <button
            onClick={() => run(directives.remove(directive.id))}
            disabled={busy}
            className="px-2.5 py-0.5 rounded-md bg-danger/90 hover:bg-danger disabled:opacity-30 text-white"
          >
            삭제
          </button>
        </div>
      )}

      {error && <p className="mt-1.5 text-[12px] text-danger">{error}</p>}
    </li>
  );
}

function IconButton({ title, danger, onClick, children }: {
  title: string;
  danger?: boolean;
  onClick: () => void;
  children: React.ReactNode;
}) {
  return (
    <button
      title={title}
      onClick={onClick}
      className={`w-7 h-7 flex items-center justify-center rounded-lg text-text-muted hover:bg-surface-hover ${
        danger ? 'hover:text-danger' : 'hover:text-text-primary'
      }`}
    >
      <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
        {children}
      </svg>
    </button>
  );
}
