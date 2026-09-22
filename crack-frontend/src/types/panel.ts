import type { ReactNode } from 'react';

/** 오른쪽 드로어(SidePanel)의 탭 하나. T15/T18/T19가 기억·지시·상태 탭을 추가한다. */
export interface SidePanelTab {
  id: string;
  label: string;
  /** 탭 이름 옆에 붙일 작은 표시 (T18: 켜진 지시 개수). 없으면 생략 */
  badge?: ReactNode;
  content: ReactNode;
}
