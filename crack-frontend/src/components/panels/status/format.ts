import type { StatusEvent } from '../../../api/status';

/** 인물 카드에 보이는 최근 사건 수 */
export const RECENT_EVENT_COUNT = 3;

/**
 * 최근 사건 n개 (최근 것부터). 끝 턴이 큰 것이 최근이고, 같으면 문서에서 뒤에 있는 것이 최근이다.
 * 턴 표기가 없는 사건은 턴이 있는 사건보다 뒤로 보낸다.
 */
export function recentEvents(events: StatusEvent[], n = RECENT_EVENT_COUNT): StatusEvent[] {
  return events
    .map((event, index) => ({ event, index }))
    .sort((a, b) => (b.event.toTurn ?? -1) - (a.event.toTurn ?? -1) || b.index - a.index)
    .slice(0, n)
    .map(({ event }) => event);
}

/** 사건의 턴 표기: `t18–21`, `t18`. 없으면 null */
export function eventTurnLabel(e: StatusEvent): string | null {
  if (e.fromTurn === null) return null;
  if (e.toTurn === null || e.toTurn === e.fromTurn) return `t${e.fromTurn}`;
  return `t${e.fromTurn}–${e.toTurn}`;
}
