import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { authApi } from '../api/auth';

export default function LoginPage() {
  const [password, setPassword] = useState('');
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(false);
  const navigate = useNavigate();

  const handleLogin = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!password.trim()) return;

    setLoading(true);
    setError('');

    try {
      const { data } = await authApi.login(password);
      localStorage.setItem('crack-token', data.token);
      navigate('/', { replace: true });
    } catch {
      setError('비밀번호가 올바르지 않습니다');
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="h-full flex items-center justify-center bg-bg-primary px-8">
      <div className="w-full max-w-sm">
        <div className="text-center mb-14">
          <h1 className="text-4xl font-bold text-text-primary tracking-tight">Crack</h1>
          <p className="text-text-muted text-sm mt-3 tracking-wide">AI Character Chat</p>
        </div>

        <form onSubmit={handleLogin} className="space-y-5">
          <div>
            <input
              type="password"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              placeholder="비밀번호를 입력하세요"
              autoFocus
              className="w-full px-5 py-4 bg-surface border border-border rounded-2xl text-text-primary placeholder-text-muted focus:outline-none focus:border-accent/60 transition-all text-[15px]"
            />
          </div>

          {error && (
            <p className="text-danger text-sm text-center py-1">{error}</p>
          )}

          <button
            type="submit"
            disabled={loading || !password.trim()}
            className="w-full py-4 bg-accent hover:bg-accent-hover disabled:opacity-30 text-white font-semibold rounded-2xl transition-all text-[15px]"
          >
            {loading ? '로그인 중...' : '로그인'}
          </button>
        </form>
      </div>
    </div>
  );
}
