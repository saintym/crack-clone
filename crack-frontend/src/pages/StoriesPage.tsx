import { useCallback, useEffect, useState } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import { scenarioApi, type Scenario } from '../api/scenarios';
import { storyApi, type Story } from '../api/stories';
import MobileLayout from '../components/layout/MobileLayout';

export default function StoriesPage() {
  const { scenarioName } = useParams<{ scenarioName: string }>();
  const navigate = useNavigate();
  const [scenario, setScenario] = useState<Scenario | null>(null);
  const [stories, setStories] = useState<Story[]>([]);
  const [loading, setLoading] = useState(true);
  const [showCreate, setShowCreate] = useState(false);
  const [newTitle, setNewTitle] = useState('');

  const loadData = useCallback(() => {
    if (!scenarioName) return;
    // setState를 then 콜백 안에서 호출해야 react-hooks/set-state-in-effect가
    // 비동기 갱신으로 인식한다 (async/await 본문은 동기 호출로 판정됨).
    return scenarioApi
      .get(scenarioName)
      .then(({ data: sc }) => {
        setScenario(sc);
        return storyApi.list(sc.id);
      })
      .then(({ data: st }) => setStories(st))
      .catch((err) => console.error('Failed to load:', err))
      .finally(() => setLoading(false));
  }, [scenarioName]);

  useEffect(() => {
    loadData();
  }, [loadData]);

  const handleCreate = async () => {
    if (!scenario || !newTitle.trim()) return;
    try {
      const { data } = await storyApi.create(scenario.id, { title: newTitle.trim() });
      setShowCreate(false);
      setNewTitle('');
      navigate(`/chat/${data.id}`);
    } catch (err) {
      console.error('Failed to create story:', err);
    }
  };

  const handleDelete = async (storyId: number) => {
    if (!scenario || !confirm('이 스토리를 삭제하시겠습니까?')) return;
    try {
      await storyApi.delete(scenario.id, storyId);
      loadData();
    } catch (err) {
      console.error('Failed to delete:', err);
    }
  };

  const formatDate = (dateStr: string) => {
    const d = new Date(dateStr);
    return d.toLocaleDateString('ko-KR', { month: 'short', day: 'numeric' });
  };

  return (
    <MobileLayout
      title={scenario?.title || scenarioName || ''}
      onBack={() => navigate('/')}
      rightAction={
        <div className="flex items-center gap-2">
          <button
            onClick={() => navigate(`/scenario/${scenarioName}`)}
            className="w-9 h-9 flex items-center justify-center rounded-full hover:bg-surface-hover text-text-muted hover:text-text-secondary transition-all"
          >
            <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
              <path d="M12.22 2h-.44a2 2 0 00-2 2v.18a2 2 0 01-1 1.73l-.43.25a2 2 0 01-2 0l-.15-.08a2 2 0 00-2.73.73l-.22.38a2 2 0 00.73 2.73l.15.1a2 2 0 011 1.72v.51a2 2 0 01-1 1.74l-.15.09a2 2 0 00-.73 2.73l.22.38a2 2 0 002.73.73l.15-.08a2 2 0 012 0l.43.25a2 2 0 011 1.73V20a2 2 0 002 2h.44a2 2 0 002-2v-.18a2 2 0 011-1.73l.43-.25a2 2 0 012 0l.15.08a2 2 0 002.73-.73l.22-.39a2 2 0 00-.73-2.73l-.15-.08a2 2 0 01-1-1.74v-.5a2 2 0 011-1.74l.15-.09a2 2 0 00.73-2.73l-.22-.38a2 2 0 00-2.73-.73l-.15.08a2 2 0 01-2 0l-.43-.25a2 2 0 01-1-1.73V4a2 2 0 00-2-2z" />
              <circle cx="12" cy="12" r="3" />
            </svg>
          </button>
          <button
            onClick={() => setShowCreate(true)}
            className="w-9 h-9 flex items-center justify-center rounded-full bg-accent hover:bg-accent-hover text-white transition-all"
          >
            <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round">
              <path d="M12 5v14M5 12h14" />
            </svg>
          </button>
        </div>
      }
    >
      <div className="h-full overflow-y-auto px-5 py-5 space-y-3">
        {loading ? (
          <div className="flex items-center justify-center h-40">
            <div className="w-7 h-7 border-2 border-accent border-t-transparent rounded-full animate-spin" />
          </div>
        ) : stories.length === 0 ? (
          <div className="flex flex-col items-center justify-center h-60 text-text-muted">
            <svg width="48" height="48" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.2" className="mb-3 opacity-30">
              <path d="M4 19.5A2.5 2.5 0 016.5 17H20" />
              <path d="M6.5 2H20v20H6.5A2.5 2.5 0 014 19.5v-15A2.5 2.5 0 016.5 2z" />
            </svg>
            <p className="text-sm mb-1">아직 스토리가 없습니다</p>
            <button
              onClick={() => setShowCreate(true)}
              className="mt-4 text-accent text-sm font-medium hover:text-accent-hover transition-colors"
            >
              새 스토리 시작하기
            </button>
          </div>
        ) : (
          stories.map((story) => (
            <div
              key={story.id}
              className="bg-surface rounded-2xl overflow-hidden border border-border/40 hover:border-border transition-all"
            >
              <button
                onClick={() => navigate(`/chat/${story.id}`)}
                className="w-full text-left px-5 py-4"
              >
                <h3 className="text-[15px] font-semibold text-text-primary leading-snug">{story.title}</h3>
                <div className="flex items-center gap-4 mt-2 text-xs text-text-muted">
                  <span className="flex items-center gap-1.5">
                    <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                      <path d="M21 15a2 2 0 01-2 2H7l-4 4V5a2 2 0 012-2h14a2 2 0 012 2z" />
                    </svg>
                    {story.turnCount}턴
                  </span>
                  <span>{formatDate(story.updatedAt)}</span>
                </div>
              </button>
              <div className="flex border-t border-border/30">
                <button
                  onClick={() => handleDelete(story.id)}
                  className="flex-1 py-2.5 text-xs text-text-muted hover:text-danger hover:bg-surface-hover transition-all flex items-center justify-center gap-1.5"
                >
                  <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                    <path d="M3 6h18M8 6V4a2 2 0 012-2h4a2 2 0 012 2v2M19 6l-1 14a2 2 0 01-2 2H8a2 2 0 01-2-2L5 6" />
                  </svg>
                  삭제
                </button>
              </div>
            </div>
          ))
        )}
      </div>

      {/* Create Modal */}
      {showCreate && (
        <div className="fixed inset-0 z-50 flex items-end justify-center bg-black/50 backdrop-blur-sm" onClick={() => setShowCreate(false)}>
          <div
            className="w-full max-w-lg bg-bg-secondary rounded-t-3xl px-6 pt-5 pb-8 safe-bottom border-t border-border/50"
            onClick={(e) => e.stopPropagation()}
          >
            <div className="w-10 h-1 bg-border-light/50 rounded-full mx-auto mb-6" />
            <h2 className="text-lg font-semibold text-text-primary mb-5">새 스토리</h2>
            <input
              type="text"
              value={newTitle}
              onChange={(e) => setNewTitle(e.target.value)}
              placeholder="스토리 제목"
              autoFocus
              onKeyDown={(e) => e.key === 'Enter' && handleCreate()}
              className="w-full px-5 py-4 bg-surface border border-border rounded-2xl text-text-primary placeholder-text-muted focus:outline-none focus:border-accent/60 transition-all text-[15px]"
            />
            <div className="flex gap-3 mt-5">
              <button
                onClick={() => setShowCreate(false)}
                className="flex-1 py-3.5 bg-surface hover:bg-surface-hover text-text-secondary rounded-2xl font-medium transition-all text-[15px]"
              >
                취소
              </button>
              <button
                onClick={handleCreate}
                disabled={!newTitle.trim()}
                className="flex-1 py-3.5 bg-accent hover:bg-accent-hover disabled:opacity-30 text-white rounded-2xl font-semibold transition-all text-[15px]"
              >
                시작
              </button>
            </div>
          </div>
        </div>
      )}
    </MobileLayout>
  );
}
