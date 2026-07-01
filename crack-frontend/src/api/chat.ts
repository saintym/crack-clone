import api from './client';

export interface ChatRequest {
  message: string;
  activeCharacters?: string[];
  provider?: string;
}

export const chatApi = {
  getHistory: (storyId: number) =>
    api.get<{ content: string }>(`/stories/${storyId}/chat/history`),

  complete: (storyId: number, fullResponse: string) =>
    api.post(`/stories/${storyId}/chat/complete`, { response: fullResponse }),

  editMessage: (storyId: number, messageIndex: number, content: string) =>
    api.patch(`/stories/${storyId}/chat/messages/${messageIndex}`, { content }),

  deleteMessage: (storyId: number, messageIndex: number) =>
    api.delete(`/stories/${storyId}/chat/messages/${messageIndex}`),

  streamUrl: (storyId: number) =>
    `${api.defaults.baseURL}/stories/${storyId}/chat`,

  regenerateUrl: (storyId: number) =>
    `${api.defaults.baseURL}/stories/${storyId}/chat/regenerate`,

  continueUrl: (storyId: number) =>
    `${api.defaults.baseURL}/stories/${storyId}/chat/continue`,
};
