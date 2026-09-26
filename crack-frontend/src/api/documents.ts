import api from './client';

export interface DocumentResponse {
  type: string;
  name: string;
  content: string;
}

/** `/scenarios/{name}/documents/{type}`의 type. 백엔드 `DocumentService.DocumentType`과 같다. */
export type ScenarioDocumentType =
  | 'world'
  | 'scenario'
  | 'protagonist'
  | 'prologue'
  | 'keywords'
  | 'commands'
  | 'images'
  | 'settings';

export interface CharacterInfo {
  type: string;
  name: string;
  content: string;
}

export const documentApi = {
  get: (scenarioName: string, type: ScenarioDocumentType) =>
    api.get<DocumentResponse>(`/scenarios/${scenarioName}/documents/${type}`),

  update: (scenarioName: string, type: ScenarioDocumentType, content: string) =>
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
