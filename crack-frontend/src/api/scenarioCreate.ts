import api from './client';
import { ChatApiError, createSseParser } from './chat';
import type { ImportDone, ImportQuestion, ImportResult, ImportStep } from './scenarioImport';

/**
 * 질문으로 시나리오 만들기 (DESIGN.md §11.6).
 *
 * `start` → (`answer` 반복) → `confirm`(SSE). 생성 파이프라인은 URL 가져오기와 공용이라
 * SSE 이벤트 타입(`step`/`done`)은 [scenarioImport]의 것을 그대로 쓴다.
 */

/** 질문 하나. URL 가져오기와 같은 형식이다 */
export type CreateQuestion = ImportQuestion;
export type CreateStep = ImportStep;
export type CreateDone = ImportDone;
export type CreateResult = ImportResult;

/** 미리보기의 인물 한 명. confirm 요청에도 같은 형식을 쓴다 */
export interface CreateCharacter {
  name: string;
  role: string;
  note: string;
}

/** 생성 직전 요약. 사람이 읽고 고칠 것이다 */
export interface CreatePreview {
  title: string;
  world: string;
  protagonist: string;
  opening: string;
  characters: CreateCharacter[];
}

/**
 * start와 answer가 함께 쓰는 응답.
 *
 * - `done: false`면 [questions]에 이번 라운드 질문이 온다. [preview]는 null
 * - `done: true`면 [preview]가 오고 [questions]는 비어 있다
 */
export interface CreateRoundResponse {
  jobId: string;
  round: number;
  done: boolean;
  suggestedName: string;
  title: string;
  questions: CreateQuestion[];
  preview: CreatePreview | null;
  estimatedLlmCalls: number;
  estimatedSeconds: number;
}

export interface CreateConfirmBody {
  name: string;
  title?: string;
  /** 주면 **그 목록만** 만든다(미리보기에서 지우거나 더한 결과) */
  characters?: CreateCharacter[];
}

export const scenarioCreateApi = {
  /** 씨앗 한 줄로 시작한다. 1라운드 질문이 온다 (LLM 1회) */
  start: (seed: string) =>
    api.post<CreateRoundResponse>('/scenarios/create/start', { seed }),

  /**
   * 이번 라운드 답을 보낸다. 다음 질문 또는 `done: true` + 미리보기가 온다.
   * 빈 답은 "모르겠다, 알아서 해"로 서버가 받는다. 비운 채 보내도 된다
   */
  answer: (jobId: string, answers: Record<string, string>) =>
    api.post<CreateRoundResponse>(
      `/scenarios/create/${encodeURIComponent(jobId)}/answer`,
      { answers },
    ),

  /** 시나리오 한 벌을 만든다 (SSE: step* → done | error) */
  confirm: (
    jobId: string,
    body: CreateConfirmBody,
    onStep: (step: CreateStep) => void,
    signal?: AbortSignal,
  ) =>
    streamCreate(
      `/scenarios/create/${encodeURIComponent(jobId)}/confirm`,
      body,
      onStep,
      signal,
    ),
};

async function toApiError(response: Response): Promise<ChatApiError> {
  let message = '';
  try {
    const body = (await response.json()) as { message?: unknown };
    if (typeof body.message === 'string') message = body.message;
  } catch {
    // JSON이 아니면 상태 코드만 쓴다
  }
  return new ChatApiError(response.status, message || `요청 실패 (${response.status})`);
}

/**
 * POST SSE 하나를 끝까지 읽는다 (scenarioImport와 같은 방식).
 * 생성 전 오류(이름 중복 400, 만료된 jobId 404)는 JSON으로 오므로 [ChatApiError]로 던진다.
 */
async function streamCreate(
  path: string,
  body: object,
  onStep: (step: CreateStep) => void,
  signal?: AbortSignal,
): Promise<CreateResult> {
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
    localStorage.removeItem('crack-token');
    window.location.href = '/login';
  }
  if (!response.ok) throw await toApiError(response);
  if (!response.body) return { type: 'interrupted' };

  let result: CreateResult = { type: 'interrupted' };
  const parser = createSseParser((name, data) => {
    if (result.type !== 'interrupted') return;
    switch (name) {
      case 'step':
        onStep(JSON.parse(data) as CreateStep);
        break;
      case 'done':
        result = { type: 'done', done: JSON.parse(data) as CreateDone };
        break;
      case 'error':
        result = { type: 'error', message: data || '시나리오를 만들지 못했습니다' };
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
