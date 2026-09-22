import type { SidePanelTab } from '../../../types/panel';
import type { MemoryStatus } from '../../../api/memory';
import StatusPanelLoader from './StatusPanelLoader';

/**
 * SidePanel에 넣을 "상태" 탭. ChatPage는 `panelTabs`에 이것을 넣기만 한다.
 * `memory`는 useMemoryStatus의 `status`다. 기록이 끝나면 패널이 스스로 다시 받는다(D13).
 * 스토리를 옮기면 패널 안 상태를 버리도록 storyId를 key로 쓴다.
 */
export function statusPanelTab(storyId: number, memory: MemoryStatus): SidePanelTab {
  return {
    id: 'status',
    label: '상태',
    content: <StatusPanelLoader key={storyId} storyId={storyId} memory={memory} />,
  };
}
