import type { SidePanelTab } from '../../../types/panel';
import type { DirectivesState } from './useDirectives';
import DirectivesPanel from './DirectivesPanel';

/** 탭 id. `/ooc`만 입력하면 ChatPage가 이 탭으로 패널을 연다 */
export const DIRECTIVES_TAB_ID = 'directives';

/**
 * SidePanel에 넣을 "지시" 탭. 켜진 지시 개수를 탭 머리에 작은 숫자로 보인다.
 * 스토리를 옮기면 패널 안 상태(입력 중인 지시, 편집 중인 항목)를 버리도록 storyId를 key로 쓴다.
 */
export function directivesPanelTab(storyId: number, directives: DirectivesState): SidePanelTab {
  return {
    id: DIRECTIVES_TAB_ID,
    label: '지시',
    badge: directives.enabledCount > 0 ? (
      <span className="ml-1.5 inline-flex min-w-4 h-4 px-1 items-center justify-center rounded-full bg-accent/80 text-white text-[10px] font-semibold leading-none align-[1px]">
        {directives.enabledCount}
      </span>
    ) : undefined,
    content: <DirectivesPanel key={storyId} directives={directives} />,
  };
}
