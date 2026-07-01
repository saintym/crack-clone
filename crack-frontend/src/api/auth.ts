import api from './client';

export const authApi = {
  login: (password: string) =>
    api.post<{ token: string }>('/auth/login', { password }),

  verify: () => api.get('/auth/verify'),
};
