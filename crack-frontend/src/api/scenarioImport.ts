import api from './client';
import { ChatApiError, createSseParser } from './chat';

/** 페이지에서 찾은 인물 (DESIGN.md §11.2) */
export interface ImportCharacter {
  name: string;
  alias: string;
  org: string;
  imageUrl: string;
}

/** 페이지에 없어서 사용자에게 묻는 질문 */
export interface ImportQuestion {
  id: string;
  text: string;
  placeholder: string;
}

export interface ImportTruncation {
  bodyChars: number;
  dataChars: number;
  droppedBodyChars: number;
  droppedBlocks: number;
}

export interface ImportAnalysis {
  jobId: string;
  url: string;
  suggestedName: string;
  title: string;
  characters: ImportCharacter[];
  characterCount: number;
  imageCount: number;
  questions: ImportQuestion[];
  truncated: ImportTruncation;
  estimatedLlmCalls: number;
  estimatedSeconds: number;
}

export interface ImportStep {
  step: string;
  label: string;
  index: number;
  total: number;
  percent: number;
  detail: string;
}

export interface ImportDone {
  scenarioId: number;
  name: string;
  title: string;
  files: string[];
  characterCount: number;
  llmCalls: number;
  elapsedSeconds: number;
}

export interface ImportConfirmBody {
  name: string;
  title?: string;
  answers: Record<string, string>;
}

/** 생성 결과. `interrupted`는 done/error 없이 연결이 끊긴 것(서버는 계속 만든다) */
export type ImportResult =
  | { type: 'done'; done: ImportDone }
  | { type: 'error'; message: string }
  | { type: 'interrupted' };

export const scenarioImportApi = {
  /** 1단계: 페이지를 읽고 제목·인물·질문을 받는다 (LLM 1회, 20~40초) */
  analyze: (url: string) =>
    api.post<ImportAnalysis>('/scenarios/import/analyze', { url }),

  /** 2단계: 시나리오 한 벌을 만든다 (SSE: step* → done | error) */
  confirm: (
    jobId: string,
    body: ImportConfirmBody,
    onStep: (step: ImportStep) => void,
    signal?: AbortSignal,
  ) => streamImport(`/scenarios/import/${encodeURIComponent(jobId)}/confirm`, body, onStep, signal),
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
 * POST SSE 하나를 끝까지 읽는다. 채팅(chat.ts)과 같은 방식이다.
 * 생성 전 오류(400/404)는 JSON으로 오므로 [ChatApiError]로 던진다.
 */
async function streamImport(
  path: string,
  body: object,
  onStep: (step: ImportStep) => void,
  signal?: AbortSignal,
): Promise<ImportResult> {
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

  let result: ImportResult = { type: 'interrupted' };
  const parser = createSseParser((name, data) => {
    if (result.type !== 'interrupted') return;
    switch (name) {
      case 'step':
        onStep(JSON.parse(data) as ImportStep);
        break;
      case 'done':
        result = { type: 'done', done: JSON.parse(data) as ImportDone };
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
