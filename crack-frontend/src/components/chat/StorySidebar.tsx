import { useNavigate } from 'react-router-dom';
import type { Story } from '../../api/stories';
import type { Scenario } from '../../api/scenarios';

interface StorySidebarProps {
  open: boolean;
  onClose: () => void;
  stories: Story[];
  currentStoryId: number;
  scenario: Scenario | null;
}

/** 왼쪽 사이드바: 같은 시나리오의 스토리 목록. 모바일에서는 오버레이로 열린다 */
export default function StorySidebar({ open, onClose, stories, currentStoryId, scenario }: StorySidebarProps) {
  const navigate = useNavigate();

  return (
    <>
      {/* Sidebar overlay for mobile */}
      {open && (
        <div
          className="fixed inset-0 z-30 bg-black/50 lg:hidden"
          onClick={onClose}
        />
      )}

      <aside
        className={`fixed z-40 top-0 left-0 h-full w-72 bg-bg-secondary border-r border-border/50 flex flex-col transition-transform lg:static lg:translate-x-0 ${
          open ? 'translate-x-0' : '-translate-x-full'
        }`}
      >
        <div className="flex items-center justify-between px-4 py-4 border-b border-border/50">
          <span className="text-sm font-medium text-text-secondary">채팅 내역</span>
          <button
            onClick={onClose}
            className="lg:hidden text-text-muted hover:text-text-primary p-1"
          >
            <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
              <path d="M18 6L6 18M6 6l12 12" />
            </svg>
          </button>
        </div>

        <div className="flex-1 overflow-y-auto">
          {stories.map((s) => (
            <button
              key={s.id}
              onClick={() => {
                navigate(`/chat/${s.id}`);
                onClose();
              }}
              className={`w-full text-left px-4 py-3.5 border-b border-border/20 transition-all ${
                s.id === currentStoryId
                  ? 'bg-surface-active'
                  : 'hover:bg-surface-hover'
              }`}
            >
              <div className="text-[14px] font-medium text-text-primary truncate">{s.title}</div>
              <div className="text-[12px] text-text-muted mt-1 truncate">
                {s.turnCount === 0 ? '새 채팅방' : `${s.turnCount}턴`}
              </div>
            </button>
          ))}
        </div>

        {scenario && (
          <div className="p-3 border-t border-border/50">
            <button
              onClick={() => navigate(`/stories/${scenario.name}`)}
              className="w-full py-2.5 text-sm text-accent hover:text-accent-hover bg-accent-soft rounded-xl font-medium transition-all"
            >
              + 새 스토리
            </button>
          </div>
        )}
      </aside>
    </>
  );
}
