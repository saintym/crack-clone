import api from './client';

export interface Scenario {
  id: number;
  name: string;
  title: string;
  status: string;
  createdAt: string;
  updatedAt: string;
}

export interface ScenarioCreateRequest {
  name: string;
  title: string;
}

export const scenarioApi = {
  list: () => api.get<Scenario[]>('/scenarios'),
  get: (name: string) => api.get<Scenario>(`/scenarios/${name}`),
  create: (req: ScenarioCreateRequest) => api.post<Scenario>('/scenarios', req),
  delete: (name: string) => api.delete(`/scenarios/${name}`),
};
