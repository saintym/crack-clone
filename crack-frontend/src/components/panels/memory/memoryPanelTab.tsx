import type { SidePanelTab } from '../../../types/panel';
import type { MemoryState } from './useMemoryStatus';
import MemoryPanelLoader from './MemoryPanelLoader';

/**
 * SidePanel에 넣을 "기억" 탭. ChatPage는 `panelTabs`에 이것을 넣기만 한다.
 * 스토리를 옮기면 패널 안 상태(열린 문서, 편집 중 내용)를 버리도록 storyId를 key로 쓴다.
 */
export function memoryPanelTab(storyId: number, memory: MemoryState): SidePanelTab {
  return {
    id: 'memory',
    label: '기억',
    content: <MemoryPanelLoader key={storyId} storyId={storyId} memory={memory} />,
  };
}
