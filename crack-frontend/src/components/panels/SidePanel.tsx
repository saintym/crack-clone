import { useState } from 'react';
import type { SidePanelTab } from '../../types/panel';

interface SidePanelProps {
  open: boolean;
  onClose: () => void;
  /** 보여 줄 탭 목록. T15(기억), T18(지시), T19(상태)가 추가한다 */
  tabs: SidePanelTab[];
}

/**
 * 채팅 화면 오른쪽 드로어(모바일은 하단 시트). 플레이 흐름을 가리지 않도록 배경을 덮지 않는다 (D7).
 * 아직 탭이 없으므로 ChatPage는 tabs=[]를 넘기고 여는 버튼도 숨긴다.
 */
export default function SidePanel({ open, onClose, tabs }: SidePanelProps) {
  const [activeTabId, setActiveTabId] = useState<string | null>(null);

  if (!open) return null;

  const activeTab = tabs.find((t) => t.id === activeTabId) ?? tabs[0];

  return (
    <aside className="fixed z-40 inset-x-0 bottom-0 max-h-[70vh] rounded-t-3xl border-t lg:static lg:inset-auto lg:max-h-none lg:h-full lg:w-96 lg:rounded-none lg:border-t-0 lg:border-l bg-bg-secondary border-border/50 flex flex-col shrink-0 safe-bottom">
      <div className="flex items-center gap-1 px-3 py-3 border-b border-border/50">
        <div className="flex-1 flex gap-1 overflow-x-auto">
          {tabs.map((tab) => (
            <button
              key={tab.id}
              onClick={() => setActiveTabId(tab.id)}
              className={`px-3 py-1.5 rounded-full text-[13px] whitespace-nowrap transition-all ${
                tab.id === activeTab?.id
                  ? 'bg-accent-soft text-accent font-medium'
                  : 'text-text-secondary hover:bg-surface-hover'
              }`}
            >
              {tab.label}
            </button>
          ))}
        </div>
        <button onClick={onClose} title="닫기" className="text-text-muted hover:text-text-primary p-1">
          <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
            <path d="M18 6L6 18M6 6l12 12" />
          </svg>
        </button>
      </div>
      <div className="flex-1 overflow-y-auto">
        {activeTab?.content}
      </div>
    </aside>
  );
}
