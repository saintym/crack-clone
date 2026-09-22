import api from './client';

/** 이미지 카탈로그 항목 (DESIGN.md §8.5). `description`은 없으면 빈 문자열 */
export interface ImageEntry {
  tag: string;
  url: string;
  description: string;
}

export const imageApi = {
  /** 스토리가 쓰는 카탈로그(시나리오 원본 `images.md`). 파일이 없으면 빈 목록 */
  list: (storyId: number) => api.get<ImageEntry[]>(`/stories/${storyId}/images`),
};

/**
 * http/https이고 호스트가 있는 URL만 허용한다. 서버도 같은 규칙으로 거르지만 화면에 넣기 전에 한 번 더 본다.
 * 공백, 큰따옴표, 꺾쇠, 역슬래시, 백틱이 있으면 거부한다(백엔드 `ImageCatalogParser.isAllowedUrl`과 맞춘다).
 */
export function isSafeImageUrl(url: string): boolean {
  if (!url || /[\s"<>\\`]/.test(url)) return false;
  // `https:///x`는 브라우저가 `https://x/`로 고쳐 읽지만 서버는 호스트가 없다고 거부한다
  if (/^https?:\/\/\//i.test(url)) return false;
  try {
    const parsed = new URL(url);
    return (parsed.protocol === 'http:' || parsed.protocol === 'https:') && parsed.hostname !== '';
  } catch {
    return false;
  }
}

const COMMENT = /<!--[\s\S]*?(?:-->|$)/g;
const ITEM = /^\s*[-*]\s+(.*)$/;
const TAG = /^[^\s:{}|]+$/;

/**
 * `images.md` 파싱 (DESIGN.md §8.5, 백엔드 `ImageCatalogParser`와 같은 규칙). 시나리오 편집 화면의 미리보기용.
 * @returns 인식한 항목과, 항목 줄(`- …`)이지만 형식이나 URL이 맞지 않아 버린 줄
 */
export function parseImageCatalog(text: string): { entries: ImageEntry[]; rejected: string[] } {
  const entries: ImageEntry[] = [];
  const rejected: string[] = [];
  const seen = new Set<string>();
  for (const line of text.replace(COMMENT, '').split('\n')) {
    const body = ITEM.exec(line)?.[1];
    if (body === undefined) continue;
    const colon = body.indexOf(':');
    const tag = colon > 0 ? body.slice(0, colon).trim() : '';
    const rest = colon > 0 ? body.slice(colon + 1) : '';
    const bar = rest.indexOf('|');
    const url = (bar < 0 ? rest : rest.slice(0, bar)).trim();
    const description = bar < 0 ? '' : rest.slice(bar + 1).trim();
    if (!TAG.test(tag) || !isSafeImageUrl(url)) {
      rejected.push(line.trim());
      continue;
    }
    if (seen.has(tag)) continue;
    seen.add(tag);
    entries.push({ tag, url, description });
  }
  return { entries, rejected };
}
