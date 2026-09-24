import { createContext } from 'react';
import { DEFAULT_VARIANT, type ImageEntry } from '../../api/images';

/** 태그 → 항목. null이면 아직 받지 못했다(이미지 줄을 숨긴다) */
export type ImageCatalog = ReadonlyMap<string, ImageEntry> | null;

/** 채팅 화면이 받은 이미지 카탈로그 (DESIGN.md §8.5). ChatPage가 넣고 ChatBubble이 읽는다 */
export const ImageCatalogContext = createContext<ImageCatalog>(null);

export type BubbleSegment = { type: 'text'; text: string } | { type: 'image'; tag: string };

/**
 * 인물 이미지 자동 선택 (DESIGN.md §8.5, D31).
 * `{speaker}_{speakerVariant}` → `{speaker}_기본` 순으로 찾고, 없으면 null(표시하지 않는다).
 * AI가 목록에 없는 변형을 골라도 기본 이미지가 받아 준다.
 */
export function resolveCharacterImage(
  catalog: ImageCatalog,
  speaker: string | null | undefined,
  speakerVariant: string | null | undefined,
): ImageEntry | null {
  if (!catalog || !speaker) return null;
  const variant = speakerVariant?.trim();
  return (variant ? catalog.get(`${speaker}_${variant}`) : undefined)
    ?? catalog.get(`${speaker}_${DEFAULT_VARIANT}`)
    ?? null;
}

/** 한 줄에 단독으로 있는 `{{img:태그}}` */
const TAG_LINE = /^\s*\{\{\s*img\s*:\s*([^\s:{}|]+)\s*\}\}\s*$/;
/** 문장 속에 섞인 태그. 이미지로 바꾸지 않고 지운다 */
const INLINE_TAG = /\{\{\s*img\s*:[^{}\n]*\}\}/g;
/** 스트리밍 중 줄 끝에서 아직 덜 들어온 태그 (`{{`, `{{im`, `{{img:설월`, `{{img:설월}`) */
const TRAILING_PARTIAL = /\{\{(?:i(?:m(?:g(?::[^{}\n]*\}?)?)?)?)?$/;
const TAG_PREFIX = '{{img:';

/** 줄 전체가 아직 덜 들어온 태그인지 (`{`, `{{img:설`, `{{img:설월}`) */
function isPartialTagLine(line: string): boolean {
  const t = line.trim();
  if (!t) return false;
  if (TAG_PREFIX.startsWith(t)) return true;
  return t.startsWith(TAG_PREFIX) && !TAG_LINE.test(t);
}

/**
 * 말풍선 본문을 텍스트와 이미지 조각으로 나눈다 (DESIGN.md §8.5).
 * - 한 줄에 단독으로 있는 `{{img:태그}}`만 이미지 조각이 된다. 태그가 카탈로그에 있는지는 렌더링할 때 본다.
 * - 문장 속에 섞인 태그는 지운다.
 * - 스트리밍 중이면 마지막 줄(아직 줄바꿈이 오지 않은 줄)의 덜 들어온 태그를 숨긴다.
 */
export function splitImageTags(content: string, streaming = false): BubbleSegment[] {
  if (!content.includes('{')) return content ? [{ type: 'text', text: content }] : [];

  const lines = content.split('\n');
  if (streaming && lines.length > 0) {
    const lastIndex = lines.length - 1;
    const last = lines[lastIndex];
    lines[lastIndex] = isPartialTagLine(last) ? '' : last.replace(TRAILING_PARTIAL, '');
  }

  const segments: BubbleSegment[] = [];
  let buffer: string[] = [];
  const flush = () => {
    const text = buffer.join('\n');
    if (text.trim()) segments.push({ type: 'text', text });
    buffer = [];
  };
  for (const line of lines) {
    const tag = TAG_LINE.exec(line)?.[1];
    if (tag !== undefined) {
      flush();
      segments.push({ type: 'image', tag });
    } else {
      buffer.push(line.replace(INLINE_TAG, ''));
    }
  }
  flush();
  return segments;
}
