import api from './client';

export interface DocumentResponse {
  type: string;
  name: string;
  content: string;
}

export interface CharacterInfo {
  type: string;
  name: string;
  content: string;
}

export const documentApi = {
  get: (scenarioName: string, type: string) =>
    api.get<DocumentResponse>(`/scenarios/${scenarioName}/documents/${type}`),

  update: (scenarioName: string, type: string, content: string) =>
    api.put(`/scenarios/${scenarioName}/documents/${type}`, { content }),

  getCharacter: (scenarioName: string, charName: string) =>
    api.get<DocumentResponse>(`/scenarios/${scenarioName}/characters/${charName}`),

  listCharacters: (scenarioName: string) =>
    api.get<CharacterInfo[]>(`/scenarios/${scenarioName}/characters`),

  createCharacter: (scenarioName: string, name: string) =>
    api.post(`/scenarios/${scenarioName}/characters`, { name }),

  updateCharacter: (scenarioName: string, charName: string, content: string) =>
    api.put(`/scenarios/${scenarioName}/characters/${charName}`, { content }),

  deleteCharacter: (scenarioName: string, charName: string) =>
    api.delete(`/scenarios/${scenarioName}/characters/${charName}`),
};
