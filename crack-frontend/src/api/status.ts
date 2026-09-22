import api from './client';

/** 목록 항목 한 줄. `turns`는 항목 안의 `(tNN)` 표기에서 읽은 턴들이다 */
export interface StatusItem {
  text: string;
  turns: number[];
}

/** `- 대상: 내용 (t21)` 관계 항목. 콜론이 없으면 `target`은 빈 문자열 */
export interface StatusRelation {
  target: string;
  description: string;
  turns: number[];
}

/** `- t18–21: 내용` 사건 항목. 턴 표기가 없으면 턴은 null */
export interface StatusEvent {
  fromTurn: number | null;
  toTurn: number | null;
  description: string;
}

/** `state.json` (DESIGN.md §7.1) */
export interface StoryStateInfo {
  companions: string[];
  location: string | null;
  time: string | null;
  updatedAtTurn: number | null;
}

/** 주인공 `## 변화 기록` */
export interface ProtagonistStatus {
  name: string;
  relations: StatusRelation[];
  statsAndSkills: StatusItem[];
  possessions: StatusItem[];
  body: StatusItem[];
}

/** 인물 `## 기억`. `events`는 문서 순서대로 전부 */
export interface CharacterStatus {
  name: string;
  companion: boolean;
  relations: StatusRelation[];
  events: StatusEvent[];
  /** `### 소지품·기술·신체` */
  possessions: StatusItem[];
}

/** `GET /api/stories/{storyId}/status` (DESIGN.md §7.5) */
export interface StoryStatus {
  recordedThroughTurn: number;
  state: StoryStateInfo;
  /** 주인공 문서가 없으면 null */
  protagonist: ProtagonistStatus | null;
  /** 기억이 있는 인물만. 동행 인물 → 나머지 이름순 */
  characters: CharacterStatus[];
}

/** 인물 상태 API. 기억 기록이 끝났을 때만 부른다(D13) */
export const statusApi = {
  get: (storyId: number) => api.get<StoryStatus>(`/stories/${storyId}/status`),
};
