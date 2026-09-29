import type { MemoryStatus } from '../api/memory';

/** 백엔드 `MessageRole` (DESIGN.md §5.2) */
export type MessageRole = 'USER' | 'ASSISTANT';

/** 백엔드 `MessageKind`. PROLOGUE는 턴 0의 첫 메시지, CONTINUATION은 이어쓰기로 생긴 응답 */
export type MessageKind = 'NORMAL' | 'PROLOGUE' | 'CONTINUATION' | 'COMMAND';

/**
 * 화면용 메시지 (`MessageView`, DESIGN.md §5.2).
 * `emotion`·`speaker`·`speakerVariant`는 화면에 글로 출력하지 않는 내부 신호다(§5.3).
 * 서버가 필드를 더 보내도 쓰지 않을 뿐 문제가 되지 않는다.
 */
export interface Message {
  id: number;
  seq: number;
  turn: number;
  role: MessageRole;
  kind: MessageKind;
  content: string;
  /** ASSISTANT만. 첫 줄 `[감정: …]` 값. 화면에 글로 출력하지 않는다 */
  emotion: string | null;
  /** ASSISTANT만. 첫 줄 `[인물: …]`의 인물 이름. 인물 이미지 선택에 쓴다(§8.5) */
  speaker: string | null;
  /** ASSISTANT만. 인물 이미지 변형 이름. 없으면 `기본`으로 폴백한다 */
  speakerVariant: string | null;
  /**
   * ASSISTANT만. 이 장면이 도달한 이야기 속 시각 (§5.4, T38). 머리글이 쓴다(T51).
   * 시간대가 붙지 않은 ISO-8601 지역 시각이다 — `2026-02-15T20:30:00`(초까지 온다).
   * 시계를 쓰지 않는 스토리(옛 스토리, `clock.enabled: false`)는 모든 메시지가 null이다.
   */
  storyTime: string | null;
  /** ASSISTANT만. 이 장면의 이야기 속 장소. 자유 문자열이다 (§5.4, T38) */
  place: string | null;
  /** ASSISTANT만. 선택된 후보 번호(0부터). USER는 null */
  variantIndex: number | null;
  /** ASSISTANT만 의미가 있다. USER는 0 */
  variantCount: number;
  edited: boolean;
  createdAt: string;
}

/** `GET /messages`의 `story`. 모르는 필드는 쓰지 않는다 */
export interface StoryChatInfo {
  turnCount: number;
  recordedThroughTurn: number;
  /** 이 스토리에서 응답을 생성하는 중이면 true (그동안 생성·수정·삭제는 409) */
  generating: boolean;
  /** 기억 기록 상태 (T14, DESIGN.md §7.2). 채팅 헤더의 기억 뱃지가 쓴다 */
  memory: MemoryStatus;
}

/** `GET /messages` 응답 */
export interface MessagesResponse {
  story: StoryChatInfo;
  messages: Message[];
}

/** `DELETE /messages/{id}` 응답 */
export interface TruncateResult {
  storyId: number;
  minTruncatedTurn: number;
  deletedCount: number;
  turnCount: number;
}
