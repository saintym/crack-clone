import type { ReactNode } from 'react';

/** 오른쪽 드로어(SidePanel)의 탭 하나. T15/T18/T19가 기억·지시·상태 탭을 추가한다. */
export interface SidePanelTab {
  id: string;
  label: string;
  content: ReactNode;
}
