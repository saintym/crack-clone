import { useEffect, useState } from 'react';
import { imageApi, isSafeImageUrl, type ImageEntry } from '../api/images';
import type { ImageCatalog } from '../components/chat/imageTags';

/**
 * 스토리의 이미지 카탈로그(DESIGN.md §8.5). 받기 전에는 null이고, 실패하면 빈 카탈로그다(이미지 줄을 숨긴다).
 * 채팅 화면에 들어올 때마다 다시 받으므로 시나리오에서 고친 카탈로그가 바로 반영된다.
 */
export function useImageCatalog(storyId: number): ImageCatalog {
  const [state, setState] = useState<{ storyId: number; catalog: ReadonlyMap<string, ImageEntry> } | null>(null);

  useEffect(() => {
    if (!storyId) return;
    imageApi.list(storyId)
      .then(({ data }) => {
        const catalog = new Map<string, ImageEntry>();
        for (const entry of data) {
          if (isSafeImageUrl(entry.url) && !catalog.has(entry.tag)) catalog.set(entry.tag, entry);
        }
        setState({ storyId, catalog });
      })
      .catch((err) => {
        console.error('이미지 카탈로그를 받지 못했습니다:', err);
        setState({ storyId, catalog: new Map() });
      });
  }, [storyId]);

  // 스토리를 옮긴 직후에는 이전 스토리의 카탈로그를 쓰지 않는다
  return state?.storyId === storyId ? state.catalog : null;
}
