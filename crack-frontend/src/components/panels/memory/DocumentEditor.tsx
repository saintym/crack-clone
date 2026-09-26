import { useEffect, useState } from 'react';
import axios from 'axios';
import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import remarkBreaks from 'remark-breaks';
import { storyDocumentApi } from '../../../api/storyDocuments';
import { errorMessage } from '../../../api/chat';

interface DocumentEditorProps {
  storyId: number;
  path: string;
  title: string;
  /** 바뀌면 다시 받는다 (기억 기록·되돌리기로 문서가 바뀌었을 수 있을 때) */
  reloadKey: string;
  /** 마크다운이 아닌 문서(settings.json)는 원문 그대로 보여 준다 */
  plain?: boolean;
  /** 편집 형식 안내 한 줄 */
  hint?: string;
  onBack: () => void;
}

interface Loaded {
  key: string;
  /** 없는 문서(404)는 null. 저장하면 새로 만든다 */
  content: string | null;
  error: string | null;
}

/** 편집 중인 내용과, 편집을 시작할 때의 서버 내용(그사이 서버가 바뀌었는지 보려고) */
interface Editing {
  draft: string;
  base: string;
}

/** 스토리 문서 하나: 마크다운으로 보고, 텍스트 영역으로 고친다 */
export default function DocumentEditor({ storyId, path, title, reloadKey, plain, hint, onBack }: DocumentEditorProps) {
  const key = `${storyId}:${path}:${reloadKey}`;
  const [loaded, setLoaded] = useState<Loaded | null>(null);
  const [editing, setEditing] = useState<Editing | null>(null);
  const [saving, setSaving] = useState(false);
  const [saveError, setSaveError] = useState<string | null>(null);
  const [confirmDiscard, setConfirmDiscard] = useState(false);

  useEffect(() => {
    let cancelled = false;
    storyDocumentApi.get(storyId, path)
      .then(({ data }) => { if (!cancelled) setLoaded({ key, content: data.content, error: null }); })
      .catch((err) => {
        if (cancelled) return;
        if (axios.isAxiosError(err) && err.response?.status === 404) {
          setLoaded({ key, content: null, error: null });
        } else {
          setLoaded({ key, content: null, error: errorMessage(err, '문서를 불러오지 못했습니다') });
        }
      });
    return () => { cancelled = true; };
  }, [storyId, path, key]);

  const ready = loaded !== null && loaded.key.startsWith(`${storyId}:${path}:`);
  const serverContent = ready ? loaded.content : null;
  const loadError = ready ? loaded.error : null;
  const dirty = editing !== null && editing.draft !== editing.base;
  // 편집하는 동안 기록·되돌리기로 서버 내용이 바뀌었다
  const changedWhileEditing = editing !== null && ready && !loadError && (serverContent ?? '') !== editing.base;

  const startEdit = () => {
    const base = serverContent ?? '';
    setEditing({ draft: base, base });
    setSaveError(null);
  };

  const save = () => {
    if (!editing) return;
    setSaving(true);
    setSaveError(null);
    storyDocumentApi.save(storyId, path, editing.draft)
      .then(({ data }) => {
        setLoaded({ key, content: data.content, error: null });
        setEditing(null);
      })
      .catch((err) => setSaveError(errorMessage(err, '저장하지 못했습니다')))
      .finally(() => setSaving(false));
  };

  const back = () => {
    if (dirty) setConfirmDiscard(true);
    else onBack();
  };

  return (
    <div className="p-3 flex flex-col min-h-full">
      <div className="flex items-center justify-between gap-2 mb-3">
        <button onClick={back} className="flex items-center gap-1 text-[13px] text-text-secondary hover:text-text-primary min-w-0">
          <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" className="shrink-0">
            <path d="M15 18l-6-6 6-6" />
          </svg>
          <span className="truncate">{title}</span>
        </button>
        {ready && !loadError && !editing && (
          <button
            onClick={startEdit}
            className="px-3 py-1 rounded-full text-[12px] text-text-secondary border border-border hover:bg-surface-hover"
          >
            편집
          </button>
        )}
        {editing && (
          <div className="flex gap-1.5">
            <button
              onClick={() => { setEditing(null); setSaveError(null); setConfirmDiscard(false); }}
              disabled={saving}
              className="px-3 py-1 rounded-full text-[12px] text-text-secondary hover:bg-surface-hover"
            >
              취소
            </button>
            <button
              onClick={save}
              disabled={saving || !dirty}
              className="px-3 py-1 rounded-full text-[12px] bg-accent text-white hover:bg-accent-hover disabled:opacity-50"
            >
              {saving ? '저장 중…' : '저장'}
            </button>
          </div>
        )}
      </div>

      {confirmDiscard && (
        <div className="mb-3 rounded-xl border border-border bg-bg-tertiary px-3 py-2.5 text-[12px]">
          <p className="text-text-primary">저장하지 않은 변경이 있습니다. 버리고 나갈까요?</p>
          <div className="mt-2 flex justify-end gap-2">
            <button onClick={() => setConfirmDiscard(false)} className="px-3 py-1 rounded-full text-text-secondary hover:bg-surface-hover">
              계속 편집
            </button>
            <button onClick={onBack} className="px-3 py-1 rounded-full text-danger hover:bg-danger/10">
              버리기
            </button>
          </div>
        </div>
      )}

      {changedWhileEditing && (
        <div className="mb-3 rounded-xl border border-accent/30 bg-accent-soft px-3 py-2.5 text-[12px]">
          <p className="text-text-primary">편집하는 동안 기억 기록으로 이 문서가 바뀌었습니다. 지금 저장하면 그 변경을 덮어씁니다.</p>
          <div className="mt-2 flex justify-end">
            <button
              onClick={() => { const base = serverContent ?? ''; setEditing({ draft: base, base }); }}
              className="px-3 py-1 rounded-full text-accent hover:bg-accent/10"
            >
              바뀐 내용으로 다시 편집
            </button>
          </div>
        </div>
      )}

      {hint && <p className="mb-2 text-[12px] text-text-muted break-words">{hint}</p>}
      {saveError && <p className="mb-2 text-[12px] text-danger">{saveError}</p>}
      {loadError && <p className="text-[13px] text-danger">{loadError}</p>}
      {!ready && <p className="text-[13px] text-text-muted">불러오는 중…</p>}

      {ready && !loadError && editing && (
        <textarea
          value={editing.draft}
          onChange={(e) => setEditing({ ...editing, draft: e.target.value })}
          spellCheck={false}
          className="flex-1 min-h-[50vh] w-full resize-none rounded-xl border border-border bg-bg-primary px-3 py-2.5 font-mono text-[13px] leading-[1.6] text-text-primary focus:outline-none focus:border-accent/60"
        />
      )}

      {ready && !loadError && !editing && (
        serverContent?.trim()
          ? plain
            ? (
              <pre className="whitespace-pre-wrap break-words font-mono text-[13px] leading-[1.6] text-text-primary">
                {serverContent}
              </pre>
            )
            : (
              <div className="chat-markdown text-[14px] leading-[1.7] break-words text-text-primary">
                <ReactMarkdown remarkPlugins={[remarkGfm, remarkBreaks]}>{serverContent}</ReactMarkdown>
              </div>
            )
          : <p className="text-[13px] text-text-muted">{serverContent === null ? '아직 없는 문서입니다. 편집해서 만들 수 있습니다.' : '비어 있습니다.'}</p>
      )}
    </div>
  );
}
