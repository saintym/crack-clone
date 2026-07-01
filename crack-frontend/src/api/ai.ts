import api from './client';

export interface AiProviders {
  default: string;
  available: string[];
}

export const aiApi = {
  getProviders: () => api.get<AiProviders>('/ai/providers'),
};
