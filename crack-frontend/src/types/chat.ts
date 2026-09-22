/** 백엔드 `MessageRole` (DESIGN.md §5.2) */
export type MessageRole = 'USER' | 'ASSISTANT';

/** 백엔드 `MessageKind`. PROLOGUE는 턴 0의 첫 메시지, CONTINUATION은 이어쓰기로 생긴 응답 */
export type MessageKind = 'NORMAL' | 'PROLOGUE' | 'CONTINUATION' | 'COMMAND';

/**
 * 화면용 메시지 (`MessageView`, DESIGN.md §5.2). 감정 값은 서버가 넣지 않는다(§5.3).
 * 서버가 필드를 더 보내도 쓰지 않을 뿐 문제가 되지 않는다.
 */
export interface Message {
  id: number;
  seq: number;
  turn: number;
  role: MessageRole;
  kind: MessageKind;
  content: string;
  /** ASSISTANT만. 선택된 후보 번호(0부터). USER는 null */
  variantIndex: number | null;
  /** ASSISTANT만 의미가 있다. USER는 0 */
  variantCount: number;
  edited: boolean;
  createdAt: string;
}

/** `GET /messages`의 `story`. 모르는 필드(예: T14의 `memory`)는 쓰지 않는다 */
export interface StoryChatInfo {
  turnCount: number;
  recordedThroughTurn: number;
  /** 이 스토리에서 응답을 생성하는 중이면 true (그동안 생성·수정·삭제는 409) */
  generating: boolean;
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
