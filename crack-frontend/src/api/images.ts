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

/** 변형을 적지 않았을 때 쓰는 기본 이미지 이름 (DESIGN.md §8.5) */
export const DEFAULT_VARIANT = '기본';

/** 인물 이미지 태그 `{인물}_{변형}` */
export const characterTag = (name: string, variant: string = DEFAULT_VARIANT) => `${name}_${variant}`;

/** 태그로 쓸 수 있는 이름인지 (백엔드 `ImageCatalogParser`와 같은 규칙) */
export const isValidImageTag = (tag: string): boolean => TAG.test(tag);

const LINE_ITEM = /^\s*[-*]\s+(.*)$/;

/** `images.md` 한 줄 */
function formatEntry(tag: string, url: string, description: string): string {
  return `- ${tag}: ${url}${description ? ` | ${description}` : ''}`;
}

/** HTML 주석 안에 있는 위치를 표시한다. 주석 안의 항목은 꺼 둔 것이라 건드리지 않는다 */
function commentMask(text: string): boolean[] {
  const mask = new Array<boolean>(text.length).fill(false);
  const re = new RegExp(COMMENT.source, 'g');
  for (let m = re.exec(text); m !== null; m = re.exec(text)) {
    for (let i = m.index; i < m.index + m[0].length; i++) mask[i] = true;
    if (m[0].length === 0) break;
  }
  return mask;
}

/** 줄 단위로 훑으면서 [tag] 항목 줄을 [replace]가 돌려준 값으로 바꾼다. null이면 그 줄을 지운다 */
function rewriteEntry(text: string, tag: string, replace: (line: string) => string | null): { text: string; found: boolean } {
  const mask = commentMask(text);
  const lines = text.split('\n');
  const out: string[] = [];
  let offset = 0;
  let found = false;
  for (const line of lines) {
    const start = offset;
    offset += line.length + 1;
    const body = LINE_ITEM.exec(line)?.[1];
    if (found || body === undefined || mask[start]) {
      out.push(line);
      continue;
    }
    const colon = body.indexOf(':');
    if (colon <= 0 || body.slice(0, colon).trim() !== tag) {
      out.push(line);
      continue;
    }
    found = true;
    const next = replace(line);
    if (next !== null) out.push(next);
  }
  return { text: out.join('\n'), found };
}

/**
 * `images.md` 텍스트에 항목 하나를 넣거나 고친다 (DESIGN.md §8.5).
 * 이미 있는 태그면 그 줄만 바꾸고, 없으면 파일 끝에 붙인다. 주석과 다른 줄은 그대로 둔다.
 */
export function upsertImageEntry(text: string, tag: string, url: string, description = ''): string {
  const line = formatEntry(tag, url.trim(), description.trim());
  const { text: rewritten, found } = rewriteEntry(text, tag, () => line);
  if (found) return rewritten;
  const base = text === '' || text.endsWith('\n') ? text : `${text}\n`;
  return `${base}${line}\n`;
}

/** `images.md` 텍스트에서 항목 한 줄을 지운다. 없으면 그대로 돌려준다. */
export function removeImageEntry(text: string, tag: string): string {
  return rewriteEntry(text, tag, () => null).text;
}
