import { useEffect, useState } from 'react';
import { storyDocumentApi } from '../../../api/storyDocuments';
import type { StoryDocumentSummary } from '../../../api/storyDocuments';
import { errorMessage } from '../../../api/chat';
import DocumentEditor from './DocumentEditor';
import { documentName } from './format';

const CHRONICLE_PATH = 'chronicle.md';
const USER_NOTE_PATH = 'user_note.md';
// T18: 이 스토리의 키워드북·명령 (시나리오 원본에서 복사된 것. 여기서 고쳐도 원본과 다른 스토리는 그대로다)
const KEYWORDS_PATH = 'keywords.md';
const COMMANDS_PATH = 'commands.md';

interface DocumentsViewProps {
  storyId: number;
  /** 바뀌면 목록과 열린 문서를 다시 받는다 (기억 기록·되돌리기 뒤) */
  reloadKey: string;
}

interface Loaded {
  storyId: number;
  list: StoryDocumentSummary[] | null;
  error: string | null;
}

/** 기억 문서: 연대기 · 주인공 · 인물(목록 → 문서) · 유저노트 · 키워드북 · 명령. 스토리 폴더의 문서만 다룬다(D12) */
export default function DocumentsView({ storyId, reloadKey }: DocumentsViewProps) {
  const [loaded, setLoaded] = useState<Loaded | null>(null);
  const [selected, setSelected] = useState<{ path: string; title: string } | null>(null);

  useEffect(() => {
    let cancelled = false;
    storyDocumentApi.list(storyId)
      .then(({ data }) => { if (!cancelled) setLoaded({ storyId, list: data, error: null }); })
      .catch((err) => { if (!cancelled) setLoaded({ storyId, list: null, error: errorMessage(err, '문서 목록을 불러오지 못했습니다') }); });
    return () => { cancelled = true; };
  }, [storyId, reloadKey]);

  if (selected) {
    return (
      <DocumentEditor
        storyId={storyId}
        path={selected.path}
        title={selected.title}
        reloadKey={reloadKey}
        onBack={() => setSelected(null)}
      />
    );
  }

  const current = loaded?.storyId === storyId ? loaded : null;
  const list = current?.list ?? [];
  const protagonist = list.find((d) => d.kind === 'protagonist');
  const characters = list.filter((d) => d.kind === 'characters');

  const open = (path: string, title: string) => setSelected({ path, title });

  return (
    <div className="p-3 space-y-4">
      {current?.error && <p className="text-[13px] text-danger">{current.error}</p>}

      <div className="space-y-1.5">
        <DocumentRow title="연대기" hint="회차별 사건 기록" onClick={() => open(CHRONICLE_PATH, '연대기')} />
        {protagonist && (
          <DocumentRow title="주인공" hint="변화 기록" onClick={() => open(protagonist.path, '주인공')} />
        )}
        <DocumentRow title="유저노트" hint="매 턴 프롬프트에 들어가는 메모" onClick={() => open(USER_NOTE_PATH, '유저노트')} />
      </div>

      <div>
        <h3 className="px-1 mb-1.5 text-[12px] font-medium text-text-muted">인물</h3>
        {current === null && <p className="px-1 text-[13px] text-text-muted">불러오는 중…</p>}
        {current !== null && !current.error && characters.length === 0 && (
          <p className="px-1 text-[13px] text-text-muted">인물 문서가 없습니다.</p>
        )}
        <div className="space-y-1.5">
          {characters.map((d) => (
            <DocumentRow key={d.path} title={documentName(d.path)} onClick={() => open(d.path, documentName(d.path))} />
          ))}
        </div>
      </div>

      <div>
        <h3 className="px-1 mb-1.5 text-[12px] font-medium text-text-muted">이 스토리의 설정</h3>
        <div className="space-y-1.5">
          <DocumentRow title="키워드북" hint="키워드가 나오면 넣을 설정" onClick={() => open(KEYWORDS_PATH, '키워드북')} />
          <DocumentRow title="명령" hint="/ 사용자 정의 명령" onClick={() => open(COMMANDS_PATH, '명령')} />
        </div>
      </div>
    </div>
  );
}

function DocumentRow({ title, hint, onClick }: { title: string; hint?: string; onClick: () => void }) {
  return (
    <button
      onClick={onClick}
      className="w-full flex items-center justify-between gap-2 rounded-xl border border-border/50 bg-bg-primary/30 px-3 py-2.5 text-left hover:bg-surface-hover"
    >
      <span className="min-w-0">
        <span className="block text-[14px] text-text-primary truncate">{title}</span>
        {hint && <span className="block text-[11px] text-text-muted truncate">{hint}</span>}
      </span>
      <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" className="shrink-0 text-text-muted">
        <path d="M9 18l6-6-6-6" />
      </svg>
    </button>
  );
}
