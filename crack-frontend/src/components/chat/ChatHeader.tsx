import { useState } from 'react';
import type { ReactNode } from 'react';
import { useNavigate } from 'react-router-dom';
import type { Scenario } from '../../api/scenarios';

interface ChatHeaderProps {
  title: string;
  scenario: Scenario | null;
  onOpenSidebar: () => void;
  providers: string[];
  selectedProvider: string;
  onSelectProvider: (provider: string) => void;
  /** 주면 오른쪽 패널(SidePanel)을 여는 버튼을 보인다. 탭이 없으면 넘기지 않는다 */
  onOpenPanel?: () => void;
  /** 패널 버튼 오른쪽 위에 겹쳐 보일 작은 뱃지 (T15 기억 뱃지). 흐름을 끊지 않는 표시만 넣는다 */
  panelBadge?: ReactNode;
}

/** 채팅 상단 바: 사이드바 열기, 뒤로 가기, 스토리 제목, AI 프로바이더 선택 */
export default function ChatHeader({
  title, scenario, onOpenSidebar, providers, selectedProvider, onSelectProvider, onOpenPanel, panelBadge,
}: ChatHeaderProps) {
  const navigate = useNavigate();
  const [showProviderMenu, setShowProviderMenu] = useState(false);

  return (
    <header className="safe-top flex items-center justify-between px-4 py-3 bg-bg-secondary/80 backdrop-blur-md border-b border-border/50 shrink-0">
      <div className="flex items-center gap-3 min-w-0">
        <button
          onClick={onOpenSidebar}
          title="채팅 내역"
          aria-label="채팅 내역 열기"
          className="lg:hidden text-text-secondary hover:text-text-primary p-1 -ml-1"
        >
          <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round">
            <path d="M3 12h18M3 6h18M3 18h18" />
          </svg>
        </button>
        <button
          onClick={() => navigate(scenario ? `/stories/${scenario.name}` : '/')}
          title="스토리 목록"
          aria-label="스토리 목록으로"
          className="hidden lg:block text-text-secondary hover:text-text-primary transition-colors p-1 -ml-1"
        >
          <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round">
            <path d="M15 18l-6-6 6-6" />
          </svg>
        </button>
        <h1 className="text-[16px] font-semibold text-text-primary truncate">
          {title}
        </h1>
      </div>

      <div className="flex items-center gap-2">
        {onOpenPanel && (
          <button
            onClick={onOpenPanel}
            title="패널"
            className="relative text-text-secondary hover:text-text-primary p-1"
          >
            <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round">
              <rect x="3" y="4" width="18" height="16" rx="2" />
              <path d="M15 4v16" />
            </svg>
            {panelBadge && <span className="absolute top-0 right-0 pointer-events-none">{panelBadge}</span>}
          </button>
        )}

        {/* AI Provider Selector */}
        <div className="relative">
          <button
            onClick={() => setShowProviderMenu(!showProviderMenu)}
            className="flex items-center gap-1.5 px-3 py-1.5 rounded-full bg-accent-soft text-accent text-[13px] font-medium hover:bg-accent/20 transition-all"
          >
            <svg width="12" height="12" viewBox="0 0 24 24" fill="currentColor">
              <path d="M13 10V3L4 14h7v7l9-11h-7z" />
            </svg>
            {selectedProvider || 'AI'}
            <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5">
              <path d="M6 9l6 6 6-6" />
            </svg>
          </button>
          {showProviderMenu && (
            <>
              <div className="fixed inset-0 z-40" onClick={() => setShowProviderMenu(false)} />
              <div className="absolute right-0 top-full mt-2 z-50 bg-bg-tertiary border border-border/60 rounded-xl shadow-xl overflow-hidden min-w-[180px]">
                {providers.map((p) => (
                  <button
                    key={p}
                    onClick={() => {
                      onSelectProvider(p);
                      setShowProviderMenu(false);
                    }}
                    className={`w-full text-left px-4 py-3 text-sm transition-all ${
                      p === selectedProvider
                        ? 'bg-accent-soft text-accent font-medium'
                        : 'text-text-secondary hover:bg-surface-hover'
                    }`}
                  >
                    {p}
                  </button>
                ))}
              </div>
            </>
          )}
        </div>
      </div>
    </header>
  );
}
