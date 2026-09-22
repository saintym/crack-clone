import api from './client';

/** 스토리 문서 종류 (DESIGN.md §9). 인물 파일은 `characters` */
export type StoryDocumentKind =
  | 'world'
  | 'scenario'
  | 'prologue'
  | 'protagonist'
  | 'characters'
  | 'chronicle'
  | 'user_note'
  | 'keywords'
  | 'commands';

/** 목록 항목. `size`는 바이트 수. 실제로 있는 파일만 나온다 */
export interface StoryDocumentSummary {
  path: string;
  kind: StoryDocumentKind;
  size: number;
}

export interface StoryDocumentContent {
  path: string;
  kind: StoryDocumentKind;
  content: string;
}

const base = (storyId: number) => `/stories/${storyId}/documents`;

/**
 * 스토리 문서 API (DESIGN.md §9). 스토리 폴더의 문서만 읽고 쓴다(시나리오 원본과 다른 스토리는 영향 없음).
 * path 예: `chronicle.md`, `user_note.md`, `characters/설월.md`
 */
export const storyDocumentApi = {
  list: (storyId: number) =>
    api.get<StoryDocumentSummary[]>(base(storyId)),

  /** 없는 문서는 404 */
  get: (storyId: number, path: string) =>
    api.get<StoryDocumentContent>(`${base(storyId)}/content`, { params: { path } }),

  /** 저장. 없던 문서도 만든다. 이전 전 `_legacy` 스토리는 400 */
  save: (storyId: number, path: string, content: string) =>
    api.put<StoryDocumentContent>(`${base(storyId)}/content`, { content }, { params: { path } }),
};
