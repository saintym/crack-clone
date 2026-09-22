import axios from 'axios';
import api from './client';
import type { Message, MessagesResponse, TruncateResult } from '../types/chat';

/** HTTP 오류. SSE 경로도 생성 전 오류(400/404/409)는 JSON `{message, status}`로 온다 */
export class ChatApiError extends Error {
  readonly status: number;

  constructor(status: number, message: string) {
    super(message);
    this.name = 'ChatApiError';
    this.status = status;
  }
}

/** 생성 중(409)인지. 409면 입력을 잠그고 서버 생성이 끝나기를 기다린다 */
export const isConflict = (err: unknown): boolean =>
  (err instanceof ChatApiError && err.status === 409)
  || (axios.isAxiosError(err) && err.response?.status === 409);

/** fetch 중단(AbortController)인지 */
export const isAbort = (err: unknown): boolean =>
  err instanceof DOMException && err.name === 'AbortError';

/** axios·fetch 오류에서 사용자에게 보일 문구를 꺼낸다 */
export function errorMessage(err: unknown, fallback: string): string {
  if (err instanceof ChatApiError) return err.message || fallback;
  if (axios.isAxiosError(err)) {
    const data = err.response?.data as { message?: unknown } | undefined;
    if (data && typeof data.message === 'string' && data.message) return data.message;
  }
  return fallback;
}

const base = (storyId: number) => `/stories/${storyId}/messages`;

export interface SendBody { content: string; provider?: string }
export interface RegenerateBody { provider?: string; instruction?: string; messageId?: number }
export interface ContinueBody { provider?: string }

/** 메시지 API (DESIGN.md §5.2). 모든 경로는 `/api/stories/{storyId}/messages` 아래 */
export const chatApi = {
  list: (storyId: number) =>
    api.get<MessagesResponse>(base(storyId)),

  /** 후보 선택. 가장 최근 ASSISTANT만 */
  selectVariant: (storyId: number, messageId: number, index: number) =>
    api.put<Message>(`${base(storyId)}/${messageId}/variant`, { index }),

  /** 수정 (역할 무관) */
  edit: (storyId: number, messageId: number, content: string) =>
    api.patch<Message>(`${base(storyId)}/${messageId}`, { content }),

  /** 이 메시지부터 끝까지 삭제 */
  truncateFrom: (storyId: number, messageId: number) =>
    api.delete<TruncateResult>(`${base(storyId)}/${messageId}`),

  /** 유저 메시지를 저장하고 응답을 생성한다 (SSE: user → delta* → done | error) */
  send: (storyId: number, body: SendBody, handlers: StreamHandlers, signal?: AbortSignal) =>
    streamGeneration(base(storyId), body, handlers, signal),

  /**
   * 마지막 응답에 후보를 추가한다. 마지막이 USER면 그 턴의 첫 응답을 만든다 (SSE: delta* → done | error).
   * `messageId`는 대화의 마지막 메시지여야 한다(아니면 400). 프롤로그는 400.
   */
  regenerate: (storyId: number, body: RegenerateBody, handlers: StreamHandlers, signal?: AbortSignal) =>
    streamGeneration(`${base(storyId)}/regenerate`, body, handlers, signal),

  /** 유저 메시지 없이 이어쓴다. 결과는 CONTINUATION 새 메시지 (SSE: delta* → done | error) */
  continueStory: (storyId: number, body: ContinueBody, handlers: StreamHandlers, signal?: AbortSignal) =>
    streamGeneration(`${base(storyId)}/continue`, body, handlers, signal),
};

// ---- SSE ----

export interface StreamHandlers {
  /** 전송만: 저장된 유저 메시지 */
  onUser?: (message: Message) => void;
  /** 새로 받은 텍스트 조각 (누적은 호출한 쪽이 한다) */
  onDelta?: (text: string) => void;
}

/**
 * 스트림 하나의 끝.
 * - `done`: 저장된 ASSISTANT 메시지 (재생성이면 기존과 같은 id에 후보 정보가 갱신된 값)
 * - `error`: 서버가 보낸 실패 문구 (재생성이면 원래 답변은 그대로다)
 * - `interrupted`: done/error 없이 연결이 끝남. 서버는 생성·저장을 계속하므로 목록을 다시 받아야 한다
 */
export type StreamResult =
  | { type: 'done'; message: Message }
  | { type: 'error'; message: string }
  | { type: 'interrupted' };

/**
 * SSE 파서. Spring 형식(`event:이름` / `data:값`, 콜론 뒤 공백 없음)을 읽는다.
 * - 여러 줄 데이터는 `data:` 줄 여러 개로 오므로 `\n`으로 다시 잇는다.
 * - 스펙은 콜론 뒤 공백 하나를 지우지만 **지우지 않는다.** Spring은 공백을 넣지 않으므로,
 *   지우면 공백으로 시작하는 delta(단어 사이 띄어쓰기)가 깨진다.
 * - 빈 줄에서 이벤트 하나를 내보낸다. `:`로 시작하는 줄은 주석이다.
 */
export function createSseParser(onEvent: (name: string, data: string) => void) {
  let buffer = '';
  let eventName = '';
  let dataLines: string[] = [];

  const dispatch = () => {
    if (dataLines.length > 0) onEvent(eventName || 'message', dataLines.join('\n'));
    eventName = '';
    dataLines = [];
  };

  const readLine = (raw: string) => {
    const line = raw.endsWith('\r') ? raw.slice(0, -1) : raw;
    if (line === '') {
      dispatch();
      return;
    }
    if (line.startsWith(':')) return;
    const colon = line.indexOf(':');
    const field = colon < 0 ? line : line.slice(0, colon);
    const value = colon < 0 ? '' : line.slice(colon + 1);
    if (field === 'event') eventName = value;
    else if (field === 'data') dataLines.push(value);
  };

  return {
    push(chunk: string) {
      buffer += chunk;
      let newline = buffer.indexOf('\n');
      while (newline >= 0) {
        readLine(buffer.slice(0, newline));
        buffer = buffer.slice(newline + 1);
        newline = buffer.indexOf('\n');
      }
    },
    end() {
      if (buffer) readLine(buffer);
      buffer = '';
      dispatch();
    },
  };
}

/** HTTP 오류 응답 본문(JSON `{message, status}`)을 읽어 [ChatApiError]로 만든다 */
async function toApiError(response: Response): Promise<ChatApiError> {
  let message = '';
  try {
    const body = await response.json() as { message?: unknown };
    if (typeof body.message === 'string') message = body.message;
  } catch {
    // 본문이 JSON이 아니면 상태 코드만 쓴다
  }
  return new ChatApiError(response.status, message || `요청 실패 (${response.status})`);
}

/**
 * POST SSE 요청 하나를 끝까지 읽는다. POST라 `EventSource`를 못 쓰므로 fetch 스트림을 직접 파싱한다.
 * @throws ChatApiError HTTP 오류(생성 전 400/404/409 등)
 * @throws DOMException `signal`로 중단했을 때(AbortError). 서버는 생성·저장을 계속한다
 */
async function streamGeneration(
  path: string,
  body: object,
  handlers: StreamHandlers,
  signal?: AbortSignal,
): Promise<StreamResult> {
  const token = localStorage.getItem('crack-token');
  const response = await fetch(`${api.defaults.baseURL}${path}`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      Accept: 'text/event-stream',
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
    },
    body: JSON.stringify(body),
    signal,
  });

  if (response.status === 401) {
    // axios 인터셉터(client.ts)와 같은 처리
    localStorage.removeItem('crack-token');
    window.location.href = '/login';
  }
  if (!response.ok) throw await toApiError(response);
  if (!response.body) return { type: 'interrupted' };

  let result: StreamResult = { type: 'interrupted' };
  const parser = createSseParser((name, data) => {
    if (result.type !== 'interrupted') return; // 끝난 뒤에 온 것은 무시
    switch (name) {
      case 'user':
        handlers.onUser?.(JSON.parse(data) as Message);
        break;
      case 'delta':
        handlers.onDelta?.(data);
        break;
      case 'done':
        result = { type: 'done', message: JSON.parse(data) as Message };
        break;
      case 'error':
        result = { type: 'error', message: data || '응답을 생성하지 못했습니다' };
        break;
    }
  });

  const reader = response.body.getReader();
  const decoder = new TextDecoder();
  for (;;) {
    const { done, value } = await reader.read();
    if (done) break;
    parser.push(decoder.decode(value, { stream: true }));
  }
  parser.push(decoder.decode());
  parser.end();
  return result;
}
