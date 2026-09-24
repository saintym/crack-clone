import { useCallback, useEffect, useState } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import { documentApi, type CharacterInfo, type ScenarioDocumentType } from '../api/documents';
import MobileLayout from '../components/layout/MobileLayout';
import ImageCatalogList from '../components/scenario/ImageCatalogList';
import ImageCatalogEditor from '../components/scenario/ImageCatalogEditor';

type Tab = 'world' | 'scenario' | 'prologue' | 'characters' | 'protagonist' | 'images' | 'keywords' | 'commands';

/** 편집 영역 placeholder (형식 예시, DESIGN.md §8.2·§8.3·§8.5) */
const PLACEHOLDERS: Partial<Record<Tab, string>> = {
  images: '- 설월_미소: https://example.com/a.webp | 설월이 옅게 웃는 모습',
  keywords: '## 천마신교\n키워드: 천마신교, 마교, 신교\n천하를 위협하는 사파의 거대 세력. 교주는 천마라 불린다.',
  commands: '## /일기\n설명: 주인공의 하루를 일기 형식으로 정리\n프롬프트: 지금까지의 일을 주인공 시점의 일기로 써라. 이야기는 진행하지 마라.',
};

export default function ScenarioDetailPage() {
  const { scenarioName } = useParams<{ scenarioName: string }>();
  const navigate = useNavigate();
  const [tab, setTab] = useState<Tab>('world');
  const [content, setContent] = useState('');
  const [characters, setCharacters] = useState<CharacterInfo[]>([]);
  const [selectedChar, setSelectedChar] = useState<string | null>(null);
  const [editing, setEditing] = useState(false);
  const [editContent, setEditContent] = useState('');
  const [saving, setSaving] = useState(false);
  const [newCharName, setNewCharName] = useState('');
  const [showNewChar, setShowNewChar] = useState(false);

  // 탭이나 시나리오가 바뀌면 편집 상태와 선택된 캐릭터를 초기화한다.
  // effect 안의 동기 setState 대신 렌더 중 이전 값과 비교해 조정한다
  // (https://react.dev/learn/you-might-not-need-an-effect#adjusting-some-state-when-a-prop-changes).
  const viewKey = `${scenarioName ?? ''}|${tab}`;
  const [prevViewKey, setPrevViewKey] = useState(viewKey);
  if (prevViewKey !== viewKey) {
    setPrevViewKey(viewKey);
    if (scenarioName) {
      setEditing(false);
      setSelectedChar(null);
    }
  }

  // setState를 then 콜백 안에서 호출해야 react-hooks/set-state-in-effect가
  // 비동기 갱신으로 인식한다 (async/await 본문은 동기 호출로 판정됨).
  const loadDocument = useCallback(
    (type: ScenarioDocumentType) =>
      documentApi
        .get(scenarioName!, type)
        .then(({ data }) => setContent(data.content))
        .catch(() => setContent('')),
    [scenarioName],
  );

  const loadCharacters = useCallback(
    () =>
      documentApi
        .listCharacters(scenarioName!)
        .then(({ data }) => setCharacters(data))
        .catch(() => setCharacters([])),
    [scenarioName],
  );

  useEffect(() => {
    if (!scenarioName) return;
    if (tab === 'characters') {
      loadCharacters();
    } else {
      loadDocument(tab);
      // 이미지 탭의 인물별 편집기가 인물 목록을 쓴다 (DESIGN.md §8.5)
      if (tab === 'images') loadCharacters();
    }
  }, [tab, scenarioName, loadCharacters, loadDocument]);

  const loadCharacter = async (name: string) => {
    try {
      const { data } = await documentApi.getCharacter(scenarioName!, name);
      setContent(data.content);
      setSelectedChar(name);
    } catch {
      setContent('');
    }
  };

  const handleSave = async () => {
    if (!scenarioName) return;
    setSaving(true);
    try {
      if (selectedChar) {
        await documentApi.updateCharacter(scenarioName, selectedChar, editContent);
      } else if (tab !== 'characters') {
        await documentApi.update(scenarioName, tab, editContent);
      }
      setContent(editContent);
      setEditing(false);
    } catch (err) {
      console.error('Save failed:', err);
    } finally {
      setSaving(false);
    }
  };

  /** 인물별 편집기가 고친 `images.md` 저장 (DESIGN.md §8.5) */
  const saveImages = (next: string): Promise<boolean> => {
    if (!scenarioName) return Promise.resolve(false);
    return documentApi
      .update(scenarioName, 'images', next)
      .then(() => { setContent(next); return true; })
      .catch((err) => { console.error('Save failed:', err); return false; });
  };

  const handleCreateChar = async () => {
    if (!scenarioName || !newCharName.trim()) return;
    try {
      await documentApi.createCharacter(scenarioName, newCharName.trim());
      setNewCharName('');
      setShowNewChar(false);
      loadCharacters();
    } catch (err) {
      console.error('Create failed:', err);
    }
  };

  const handleDeleteChar = async (name: string) => {
    if (!scenarioName || !confirm(`"${name}" 캐릭터를 삭제하시겠습니까?`)) return;
    try {
      await documentApi.deleteCharacter(scenarioName, name);
      setSelectedChar(null);
      loadCharacters();
    } catch (err) {
      console.error('Delete failed:', err);
    }
  };

  const tabs: { key: Tab; label: string }[] = [
    { key: 'world', label: '세계관' },
    { key: 'scenario', label: '시나리오' },
    { key: 'prologue', label: '첫 메시지' },
    { key: 'protagonist', label: '주인공' },
    { key: 'characters', label: '캐릭터' },
    { key: 'images', label: '이미지' },
    { key: 'keywords', label: '키워드북' },
    { key: 'commands', label: '명령' },
  ];

  return (
    <MobileLayout
      title={scenarioName || ''}
      onBack={() => navigate('/')}
      rightAction={
        <button
          onClick={() => navigate(`/stories/${scenarioName}`)}
          className="text-accent text-sm font-medium"
        >
          스토리
        </button>
      }
    >
      {/* Tabs */}
      <div className="flex overflow-x-auto border-b border-border/40 bg-bg-secondary/60">
        {tabs.map((t) => (
          <button
            key={t.key}
            onClick={() => setTab(t.key)}
            className={`flex-1 shrink-0 px-3 py-3.5 whitespace-nowrap text-[13px] font-medium transition-all ${
              tab === t.key
                ? 'text-accent border-b-2 border-accent'
                : 'text-text-muted hover:text-text-secondary'
            }`}
          >
            {t.label}
          </button>
        ))}
      </div>

      <div className="h-[calc(100%-48px)] overflow-y-auto">
        {tab === 'characters' && !selectedChar ? (
          /* Character List */
          <div className="px-5 py-5 space-y-3">
            {characters.map((c) => (
              <div
                key={c.name}
                className="flex items-center justify-between bg-surface rounded-2xl p-5 border border-border/40 hover:border-border transition-all"
              >
                <button
                  onClick={() => loadCharacter(c.name)}
                  className="flex-1 text-left"
                >
                  <span className="text-[15px] text-text-primary font-medium">{c.name}</span>
                </button>
                <button
                  onClick={() => handleDeleteChar(c.name)}
                  className="w-8 h-8 flex items-center justify-center rounded-full hover:bg-surface-hover text-text-muted hover:text-danger transition-all"
                >
                  <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                    <path d="M18 6L6 18M6 6l12 12" />
                  </svg>
                </button>
              </div>
            ))}

            {showNewChar ? (
              <div className="flex gap-3 items-center">
                <input
                  type="text"
                  value={newCharName}
                  onChange={(e) => setNewCharName(e.target.value)}
                  placeholder="캐릭터 이름"
                  autoFocus
                  className="flex-1 px-4 py-3.5 bg-surface border border-border/60 rounded-2xl text-text-primary placeholder-text-muted focus:outline-none focus:border-accent/60 text-[15px] transition-all"
                />
                <button onClick={handleCreateChar} className="px-5 py-3.5 bg-accent hover:bg-accent-hover text-white rounded-2xl text-sm font-medium transition-all">추가</button>
                <button onClick={() => { setShowNewChar(false); setNewCharName(''); }} className="px-4 py-3.5 bg-surface hover:bg-surface-hover text-text-muted rounded-2xl text-sm transition-all">취소</button>
              </div>
            ) : (
              <button
                onClick={() => setShowNewChar(true)}
                className="w-full py-4 border border-dashed border-border/60 rounded-2xl text-text-muted text-sm hover:border-accent/60 hover:text-accent transition-all"
              >
                + 새 캐릭터
              </button>
            )}
          </div>
        ) : (
          /* Document Editor */
          <div className="px-5 py-5 flex flex-col h-full">
            {selectedChar && (
              <button
                onClick={() => { setSelectedChar(null); setEditing(false); }}
                className="flex items-center gap-1.5 text-sm text-accent mb-4 text-left hover:text-accent-hover transition-colors"
              >
                <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round">
                  <path d="M15 18l-6-6 6-6" />
                </svg>
                캐릭터 목록
              </button>
            )}

            {tab === 'prologue' && (
              <p className="text-xs text-text-muted mb-3 leading-relaxed">
                스토리를 시작하면 AI의 첫 메시지로 들어갑니다. {'{{user}}'}는 주인공 이름으로 바뀌고,
                비워 두면 첫 메시지 없이 시작합니다. 이미 만든 스토리에는 반영되지 않습니다.
              </p>
            )}

            {tab === 'images' && (
              <p className="text-xs text-text-muted mb-3 leading-relaxed">
                이미지를 다른 곳(예: imgur, 개인 스토리지)에 올리고 <strong>주소만</strong> 붙여 넣습니다(http/https).
                인물마다 <code className="font-mono">기본</code> 이미지를 넣으면 그 인물이 나오는 턴에 자동으로 표시되고,
                표정을 더하면 AI가 장면에 맞는 것을 고릅니다. AI에게는 태그와 설명만 보냅니다.
                진행 중인 스토리에도 바로 반영됩니다.
              </p>
            )}

            {tab === 'keywords' && (
              <p className="text-xs text-text-muted mb-3 leading-relaxed">
                <code className="font-mono">## 제목</code> 아래 <code className="font-mono">키워드: 이름, 별칭</code> 줄과 넣을 내용을 적습니다.
                최근 대화에 키워드가 나오면 그 항목이 프롬프트에 들어가고, 위에 있는 항목이 우선합니다.
                새로 만드는 스토리에 복사됩니다. 진행 중인 스토리는 채팅 화면 기억 패널의 문서에서 고칩니다.
              </p>
            )}

            {tab === 'commands' && (
              <p className="text-xs text-text-muted mb-3 leading-relaxed">
                <code className="font-mono">## /이름</code> 아래 <code className="font-mono">설명:</code>과 <code className="font-mono">프롬프트:</code>를 적으면
                채팅창에서 <code className="font-mono">/이름 요청</code>으로 쓸 수 있습니다. 프롬프트는 그 턴에만 들어갑니다.
                <code className="font-mono">/기록</code>, <code className="font-mono">/ooc</code>는 시스템 명령이라 쓸 수 없습니다.
                새로 만드는 스토리에 복사됩니다. 진행 중인 스토리는 채팅 화면 기억 패널의 문서에서 고칩니다.
              </p>
            )}

            {editing ? (
              <>
                <textarea
                  value={editContent}
                  onChange={(e) => setEditContent(e.target.value)}
                  placeholder={PLACEHOLDERS[tab]}
                  className="flex-1 w-full p-4 bg-surface border border-border/40 rounded-2xl text-text-primary text-sm font-mono resize-none focus:outline-none focus:border-accent/60 transition-all min-h-[300px] leading-relaxed"
                />
                {tab === 'images' && (
                  <div className="mt-4">
                    <ImageCatalogList text={editContent} />
                  </div>
                )}
                <div className="flex gap-3 mt-4">
                  <button
                    onClick={() => setEditing(false)}
                    className="flex-1 py-3.5 bg-surface hover:bg-surface-hover text-text-secondary rounded-2xl text-[15px] font-medium transition-all"
                  >
                    취소
                  </button>
                  <button
                    onClick={handleSave}
                    disabled={saving}
                    className="flex-1 py-3.5 bg-accent hover:bg-accent-hover disabled:opacity-30 text-white rounded-2xl text-[15px] font-semibold transition-all"
                  >
                    {saving ? '저장 중...' : '저장'}
                  </button>
                </div>
              </>
            ) : tab === 'images' ? (
              <>
                <ImageCatalogEditor
                  characters={characters.map((c) => c.name)}
                  text={content}
                  onSave={saveImages}
                />
                <button
                  onClick={() => { setEditContent(content); setEditing(true); }}
                  className="mt-4 mb-6 py-3 bg-surface hover:bg-surface-hover text-text-secondary rounded-2xl text-[14px] font-medium transition-all"
                >
                  마크다운으로 직접 편집
                </button>
              </>
            ) : (
              <>
                <div className="flex-1 p-5 bg-surface border border-border/40 rounded-2xl text-sm text-text-secondary whitespace-pre-wrap overflow-y-auto font-mono min-h-[200px] leading-relaxed">
                  {content || (
                    <span className="text-text-muted italic">
                      {tab === 'prologue' ? '(없음: 첫 메시지 없이 시작)' : '(비어있음)'}
                    </span>
                  )}
                </div>
                <button
                  onClick={() => { setEditContent(content); setEditing(true); }}
                  className="mt-4 py-3.5 bg-accent hover:bg-accent-hover text-white rounded-2xl text-[15px] font-semibold transition-all"
                >
                  편집
                </button>
              </>
            )}
          </div>
        )}
      </div>
    </MobileLayout>
  );
}
