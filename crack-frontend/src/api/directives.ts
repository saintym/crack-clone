import api from './client';

/** 지속 OOC 지시 (DESIGN.md §8.1). `createdAt`은 오프셋 포함 ISO-8601 */
export interface Directive {
  id: string;
  text: string;
  enabled: boolean;
  createdAt: string;
}

const base = (storyId: number) => `/stories/${storyId}/directives`;

/**
 * 지속 지시 API (DESIGN.md §8.1). 모든 경로는 `/api/stories/{storyId}/directives` 아래.
 * 켜진 지시는 해제할 때까지 매 턴 프롬프트 맨 아래에 들어간다(D10).
 */
export const directiveApi = {
  /** 추가한 순서. 파일이 없으면 빈 목록 */
  list: (storyId: number) =>
    api.get<Directive[]>(base(storyId)),

  /** 추가(201). `text`는 서버가 앞뒤 공백을 떼고, 비면 400. `enabled` 기본 true */
  create: (storyId: number, text: string, enabled?: boolean) =>
    api.post<Directive>(base(storyId), enabled === undefined ? { text } : { text, enabled }),

  /** 준 필드만 바꾼다. `text`가 비면 400, 없는 id는 404 */
  update: (storyId: number, id: string, patch: { text?: string; enabled?: boolean }) =>
    api.patch<Directive>(`${base(storyId)}/${encodeURIComponent(id)}`, patch),

  /** 204. 없는 id는 404 */
  remove: (storyId: number, id: string) =>
    api.delete<void>(`${base(storyId)}/${encodeURIComponent(id)}`),
};
