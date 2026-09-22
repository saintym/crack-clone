import { useEffect, useState } from 'react';
import { aiApi } from '../api/ai';

/**
 * AI 프로바이더 목록과 선택값.
 * 스토리가 바뀔 때마다 목록을 다시 받고 선택값을 서버 기본값으로 되돌린다 (분리 전 동작 유지).
 */
export function useProviders(storyId: number) {
  const [providers, setProviders] = useState<string[]>([]);
  const [selectedProvider, setSelectedProvider] = useState<string>('');

  useEffect(() => {
    if (!storyId) return;
    aiApi.getProviders().then(({ data }) => {
      setProviders(data.available);
      setSelectedProvider(data.default);
    }).catch(console.error);
  }, [storyId]);

  return { providers, selectedProvider, setSelectedProvider };
}
