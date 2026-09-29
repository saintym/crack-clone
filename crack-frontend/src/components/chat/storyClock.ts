/**
 * 이야기 속 시각·장소 머리글 계산 (DESIGN.md §5.4). T38이 실어 보내는 값을 화면 값으로 바꾼다.
 *
 * 화면과 떼어 놓은 **순수 함수만** 둔다. 프론트에는 테스트 프레임워크가 없으므로
 * 형식과 "언제 그리는지" 판단을 여기 모아 눈으로 따라갈 수 있게 한다.
 * 그리는 일은 `StoryClockHeader`, 자리를 정하는 일은 `MessageList`가 한다.
 */

/** 요일 이름. 로케일에 맡기지 않는다 — 어느 브라우저에서도 결과가 같아야 한다 */
const WEEKDAYS = ['일', '월', '화', '수', '목', '금', '토'];

/** 서버가 보내는 ISO-8601 지역 시각(`2026-02-15T20:30:00`). 초는 붙어도 없어도 받는다 */
const ISO_LOCAL = /^(\d{4})-(\d{2})-(\d{2})[T ](\d{2}):(\d{2})/;

/** 파싱한 이야기 속 시각. 시간대에 흔들리지 않게 숫자와 글자로만 다룬다 */
export interface StoryTimeParts {
  /** 비교용 `2026-09-26` */
  date: string;
  /** `2026. 09. 26.` */
  dateText: string;
  /** `수요일` */
  weekday: string;
  /** `23:40` */
  time: string;
}

/**
 * `storyTime` 문자열을 화면용 조각으로 나눈다. 값이 없거나 형식이 다르면 null이다.
 *
 * `new Date(문자열)`로 해석하지 않는다 — 문자열에 시간대가 없어 브라우저·설정에 따라
 * 날짜가 하루 밀 수 있다. 요일만 UTC 자정으로 만든 날짜에서 뽑는다(시간대와 무관하다).
 *
 * ```
 * parseStoryTime('2026-09-26T23:40:00')
 * // { date: '2026-09-26', dateText: '2026. 09. 26.', weekday: '수요일', time: '23:40' }
 * parseStoryTime(null)                  // null
 * parseStoryTime('2026-02-31T10:00:00') // null (없는 날짜)
 * ```
 */
export function parseStoryTime(iso: string | null | undefined): StoryTimeParts | null {
  const match = iso ? ISO_LOCAL.exec(iso.trim()) : null;
  if (!match) return null;
  const [, yyyy, mm, dd, hh, min] = match;
  const year = Number(yyyy);
  const month = Number(mm);
  const day = Number(dd);
  if (Number(hh) > 23 || Number(min) > 59) return null;

  // 연도 0~99도 그대로 쓰려면 setUTCFullYear를 거쳐야 한다(Date.UTC는 1900년대로 옮긴다).
  const utc = new Date(0);
  utc.setUTCFullYear(year, month - 1, day);
  utc.setUTCHours(0, 0, 0, 0);
  // 2026-02-31처럼 없는 날짜는 버린다 (Date가 다음 달로 넘긴다)
  if (utc.getUTCFullYear() !== year || utc.getUTCMonth() !== month - 1 || utc.getUTCDate() !== day) return null;

  return {
    date: `${yyyy}-${mm}-${dd}`,
    dateText: `${yyyy}. ${mm}. ${dd}.`,
    weekday: `${WEEKDAYS[utc.getUTCDay()]}요일`,
    time: `${hh}:${min}`,
  };
}

/** 머리글 판단에 쓰는 메시지 조각. `Message`에서 필요한 것만 받는다 */
export interface StoryClockSource {
  turn: number;
  storyTime: string | null;
  place: string | null;
}

/** 한 자리에 그릴 머리글 값 */
export interface StoryClockHeaderData {
  /** 턴 번호 (`T47`의 47) */
  turn: number;
  /** `2026. 09. 26. 수요일` */
  when: string;
  /** 이야기 속 장소. 없으면 null */
  place: string | null;
  /** 이 장면이 **시작한** 이야기 속 시각 `23:40`. 아래 이어지는 턴에서는 분이 조금씩 흐른다 */
  time: string;
}

/**
 * 머리글 하나와 그 **비교 열쇠**. 열쇠가 직전과 같으면 다시 그리지 않는다.
 *
 * **열쇠는 날짜와 장소다. 시각은 보여 주되 비교하지 않는다.**
 * AI는 태그를 매 턴 적고 대화 한 마디에 1분씩 흘려 놓는다(T38 실측). 분까지 비교하면
 * 머리글이 매 턴 떠서 소설 흐름이 끊긴다 — 명세가 막은 것이다.
 * 장면이 바뀌면(자리를 옮기거나 날이 가면) 열쇠가 바뀌어 새 머리글이 뜨고,
 * 그 머리글의 시각은 **그 장면이 시작한 시각**이다.
 */
function headerOf(msg: StoryClockSource): { key: string; data: StoryClockHeaderData } | null {
  const parts = parseStoryTime(msg.storyTime);
  if (!parts) return null;
  const place = msg.place?.trim() || null;
  return {
    key: `${parts.date}\u0000${place ?? ''}`,
    data: {
      turn: msg.turn,
      when: `${parts.dateText} ${parts.weekday}`,
      place,
      time: parts.time,
    },
  };
}

/**
 * 메시지마다 그 **위에** 그릴 머리글을 정한다. 반환 배열은 `messages`와 길이가 같다.
 *
 * - 시각이 없는 메시지는 항상 null이고 **비교에 끼우지 않는다.** USER 메시지는 시각·장소가
 *   언제나 null이므로(§5.4) 값이 있는 ASSISTANT 메시지끼리 비교된다. 시계를 쓰지 않는
 *   스토리(옛 스토리, `clock.enabled: false`)는 전부 null이라 아무것도 그리지 않는다.
 * - 날짜와 장소가 직전과 같으면 null이다. **턴 번호만 바뀐 턴에는 그리지 않는다.**
 *
 * ```
 * storyClockHeaders([
 *   { turn: 1, storyTime: '2026-09-26T19:00:00', place: '거실' },   // 머리글 (첫 값)
 *   { turn: 2, storyTime: null,                  place: null },     // null (USER)
 *   { turn: 2, storyTime: '2026-09-26T19:20:00', place: '거실' },   // null (날짜·장소 같음)
 *   { turn: 3, storyTime: '2026-09-26T19:40:00', place: '서재' },   // 머리글 (장소 바뀜)
 *   { turn: 4, storyTime: '2026-09-27T08:00:00', place: '서재' },   // 머리글 (날짜 바뀜)
 * ])
 * ```
 */
export function storyClockHeaders(messages: readonly StoryClockSource[]): (StoryClockHeaderData | null)[] {
  let prevKey: string | null = null;
  return messages.map((msg) => {
    const header = headerOf(msg);
    if (!header) return null;
    if (header.key === prevKey) return null;
    prevKey = header.key;
    return header.data;
  });
}

/** 머리글 한 줄 (`T47 · 2026. 09. 26. 토요일 23:40. 에미야 저택`). 화면 낭독기와 눈 검증에 쓴다 */
export function storyClockLine(data: StoryClockHeaderData): string {
  return `T${data.turn} · ${data.when} ${data.time}${data.place ? `. ${data.place}` : ''}`;
}
