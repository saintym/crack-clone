import api from './client';

export interface Story {
  id: number;
  scenarioId: number;
  title: string;
  turnCount: number;
  status: string;
  createdAt: string;
  updatedAt: string;
}

export interface StoryCreateRequest {
  title: string;
}

export const storyApi = {
  list: (scenarioId: number) =>
    api.get<Story[]>(`/scenarios/${scenarioId}/stories`),

  get: (scenarioId: number, storyId: number) =>
    api.get<Story>(`/scenarios/${scenarioId}/stories/${storyId}`),

  getById: (storyId: number) =>
    api.get<Story>(`/stories/${storyId}`),

  create: (scenarioId: number, req: StoryCreateRequest) =>
    api.post<Story>(`/scenarios/${scenarioId}/stories`, req),

  archive: (scenarioId: number, storyId: number) =>
    api.patch<Story>(`/scenarios/${scenarioId}/stories/${storyId}/archive`),

  delete: (scenarioId: number, storyId: number) =>
    api.delete(`/scenarios/${scenarioId}/stories/${storyId}`),

  /** 이 메시지까지를 복사해 새 스토리를 만든다 (T09: `{messageId, title}`) */
  branch: (storyId: number, messageId: number, title: string) =>
    api.post<Story>(`/stories/${storyId}/branch`, { messageId, title }),
};
