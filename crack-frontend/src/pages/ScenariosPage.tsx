import { useCallback, useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { scenarioApi, type Scenario } from '../api/scenarios';
import MobileLayout from '../components/layout/MobileLayout';
import ScenarioImportSheet from '../components/scenario/ScenarioImportSheet';

export default function ScenariosPage() {
  const [scenarios, setScenarios] = useState<Scenario[]>([]);
  const [showCreate, setShowCreate] = useState(false);
  const [showImport, setShowImport] = useState(false);
  const [name, setName] = useState('');
  const [title, setTitle] = useState('');
  const [loading, setLoading] = useState(true);
  const navigate = useNavigate();

  // setState를 then 콜백 안에서 호출해야 react-hooks/set-state-in-effect가
  // 비동기 갱신으로 인식한다 (async/await 본문은 동기 호출로 판정됨).
  const loadScenarios = useCallback(
    () =>
      scenarioApi
        .list()
        .then(({ data }) => setScenarios(data))
        .catch((err) => console.error('Failed to load scenarios', err))
        .finally(() => setLoading(false)),
    [],
  );

  useEffect(() => { loadScenarios(); }, [loadScenarios]);

  const handleCreate = async () => {
    if (!name.trim() || !title.trim()) return;
    try {
      await scenarioApi.create({ name: name.trim(), title: title.trim() });
      setShowCreate(false);
      setName('');
      setTitle('');
      loadScenarios();
    } catch (err) {
      console.error('Failed to create scenario', err);
    }
  };

  const handleDelete = async (scenarioName: string) => {
    if (!confirm(`"${scenarioName}" 시나리오를 삭제하시겠습니까?`)) return;
    try {
      await scenarioApi.delete(scenarioName);
      loadScenarios();
    } catch (err) {
      console.error('Failed to delete scenario', err);
    }
  };

  return (
    <MobileLayout
      title="시나리오"
      rightAction={
        <div className="flex items-center gap-2">
          <button
            onClick={() => setShowImport(true)}
            title="URL로 가져오기"
            className="h-9 px-3 flex items-center gap-1.5 rounded-full bg-surface hover:bg-surface-hover text-text-secondary text-xs transition-all"
          >
            <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round">
              <path d="M10 13a5 5 0 007.54.54l3-3a5 5 0 00-7.07-7.07l-1.72 1.71" />
              <path d="M14 11a5 5 0 00-7.54-.54l-3 3a5 5 0 007.07 7.07l1.71-1.71" />
            </svg>
            URL
          </button>
          <button
            onClick={() => setShowCreate(true)}
            title="새 시나리오"
            className="w-9 h-9 flex items-center justify-center rounded-full bg-accent hover:bg-accent-hover text-white transition-all"
          >
            <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round">
              <path d="M12 5v14M5 12h14" />
            </svg>
          </button>
        </div>
      }
    >
      <div className="h-full overflow-y-auto px-5 py-5 space-y-4">
        {loading ? (
          <div className="flex items-center justify-center h-40">
            <div className="w-7 h-7 border-2 border-accent border-t-transparent rounded-full animate-spin" />
          </div>
        ) : scenarios.length === 0 ? (
          <div className="flex flex-col items-center justify-center h-60 text-text-muted">
            <svg width="52" height="52" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.2" className="mb-4 opacity-40">
              <path d="M21 15a2 2 0 01-2 2H7l-4 4V5a2 2 0 012-2h14a2 2 0 012 2z" />
            </svg>
            <p className="text-sm mb-1">아직 시나리오가 없습니다</p>
            <button
              onClick={() => setShowCreate(true)}
              className="mt-4 text-accent text-sm font-medium hover:text-accent-hover transition-colors"
            >
              새 시나리오 만들기
            </button>
            <button
              onClick={() => setShowImport(true)}
              className="mt-2 text-text-muted text-sm hover:text-text-secondary transition-colors"
            >
              URL로 가져오기
            </button>
          </div>
        ) : (
          scenarios.map((s) => (
            <div
              key={s.id}
              className="bg-surface rounded-2xl overflow-hidden border border-border/40 hover:border-border transition-all"
            >
              <button
                onClick={() => navigate(`/stories/${s.name}`)}
                className="w-full text-left px-5 py-5"
              >
                <h3 className="text-[16px] font-semibold text-text-primary leading-snug">{s.title}</h3>
                <p className="text-sm text-text-muted mt-1.5">{s.name}</p>
                <div className="flex items-center gap-4 mt-3 text-xs text-text-muted">
                  <span>{new Date(s.createdAt).toLocaleDateString('ko-KR')}</span>
                </div>
              </button>
              <div className="flex border-t border-border/30">
                <button
                  onClick={() => navigate(`/scenario/${s.name}`)}
                  className="flex-1 py-3 text-xs text-text-muted hover:text-text-secondary hover:bg-surface-hover transition-all flex items-center justify-center gap-1.5"
                >
                  <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                    <path d="M12.22 2h-.44a2 2 0 00-2 2v.18a2 2 0 01-1 1.73l-.43.25a2 2 0 01-2 0l-.15-.08a2 2 0 00-2.73.73l-.22.38a2 2 0 00.73 2.73l.15.1a2 2 0 011 1.72v.51a2 2 0 01-1 1.74l-.15.09a2 2 0 00-.73 2.73l.22.38a2 2 0 002.73.73l.15-.08a2 2 0 012 0l.43.25a2 2 0 011 1.73V20a2 2 0 002 2h.44a2 2 0 002-2v-.18a2 2 0 011-1.73l.43-.25a2 2 0 012 0l.15.08a2 2 0 002.73-.73l.22-.39a2 2 0 00-.73-2.73l-.15-.08a2 2 0 01-1-1.74v-.5a2 2 0 011-1.74l.15-.09a2 2 0 00.73-2.73l-.22-.38a2 2 0 00-2.73-.73l-.15.08a2 2 0 01-2 0l-.43-.25a2 2 0 01-1-1.73V4a2 2 0 00-2-2z" />
                    <circle cx="12" cy="12" r="3" />
                  </svg>
                  설정
                </button>
                <div className="w-px bg-border/30" />
                <button
                  onClick={() => handleDelete(s.name)}
                  className="flex-1 py-3 text-xs text-text-muted hover:text-danger hover:bg-surface-hover transition-all flex items-center justify-center gap-1.5"
                >
                  <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                    <path d="M3 6h18M8 6V4a2 2 0 012-2h4a2 2 0 012 2v2M19 6l-1 14a2 2 0 01-2 2H8a2 2 0 01-2-2L5 6" />
                  </svg>
                  삭제
                </button>
              </div>
            </div>
          ))
        )}
      </div>

      {showImport && (
        <ScenarioImportSheet
          onClose={() => { setShowImport(false); loadScenarios(); }}
          onCreated={(done) => { setShowImport(false); navigate(`/scenario/${done.name}`); }}
        />
      )}

      {/* Create Modal */}
      {showCreate && (
        <div className="fixed inset-0 z-50 flex items-end justify-center bg-black/50 backdrop-blur-sm" onClick={() => setShowCreate(false)}>
          <div
            className="w-full max-w-lg bg-bg-secondary rounded-t-3xl px-6 pt-5 pb-8 safe-bottom border-t border-border/50"
            onClick={(e) => e.stopPropagation()}
          >
            <div className="w-10 h-1 bg-border-light/50 rounded-full mx-auto mb-6" />
            <h2 className="text-lg font-semibold text-text-primary mb-5">새 시나리오</h2>

            <div className="space-y-4">
              <input
                type="text"
                value={name}
                onChange={(e) => setName(e.target.value)}
                placeholder="시나리오 ID (영문/한글, 공백 없이)"
                autoFocus
                className="w-full px-5 py-4 bg-surface border border-border rounded-2xl text-text-primary placeholder-text-muted focus:outline-none focus:border-accent/60 transition-all text-[15px]"
              />
              <input
                type="text"
                value={title}
                onChange={(e) => setTitle(e.target.value)}
                placeholder="시나리오 제목"
                className="w-full px-5 py-4 bg-surface border border-border rounded-2xl text-text-primary placeholder-text-muted focus:outline-none focus:border-accent/60 transition-all text-[15px]"
              />
            </div>

            <div className="flex gap-3 mt-6">
              <button
                onClick={() => setShowCreate(false)}
                className="flex-1 py-3.5 bg-surface hover:bg-surface-hover text-text-secondary rounded-2xl font-medium transition-all text-[15px]"
              >
                취소
              </button>
              <button
                onClick={handleCreate}
                disabled={!name.trim() || !title.trim()}
                className="flex-1 py-3.5 bg-accent hover:bg-accent-hover disabled:opacity-30 text-white rounded-2xl font-semibold transition-all text-[15px]"
              >
                생성
              </button>
            </div>
          </div>
        </div>
      )}
    </MobileLayout>
  );
}
