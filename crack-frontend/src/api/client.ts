import axios from 'axios';

const API_BASE = import.meta.env.VITE_API_URL || '';

const api = axios.create({
  baseURL: `${API_BASE}/api`,
  headers: { 'Content-Type': 'application/json' },
});

api.interceptors.request.use((config) => {
  const token = localStorage.getItem('crack-token');
  if (token) {
    config.headers.Authorization = `Bearer ${token}`;
  }
  return config;
});

api.interceptors.response.use(
  (res) => res,
  (err) => {
    // 로그인 요청 자체의 401은 "비밀번호가 틀렸다"는 뜻이다. 이때도 /login으로 보내면
    // 페이지가 통째로 새로 떠서 LoginPage의 오류 문구 state가 날아가고, 입력칸만 비워진 채
    // 아무 피드백도 남지 않는다 (BUG-020).
    const url: string = err.config?.url ?? '';
    const isLoginRequest = url.includes('/auth/login');
    if (err.response?.status === 401 && !isLoginRequest) {
      localStorage.removeItem('crack-token');
      if (window.location.pathname !== '/login') {
        window.location.href = '/login';
      }
    }
    return Promise.reject(err);
  }
);

export default api;
