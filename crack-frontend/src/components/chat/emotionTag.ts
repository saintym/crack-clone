/**
 * 응답 앞쪽 태그 줄 숨김 (DESIGN.md §5.3, 백엔드 `EmotionTagFilter`와 같은 규칙).
 * 서버가 이미 `[감정: …]`·`[인물: …]`을 떼고 보내지만, 방어용으로 화면에서 한 번 더 숨긴다.
 */

/** 태그 하나. 값에는 대괄호를 쓸 수 없다 */
const TAG = /^\[\s*(?:감정|인물)\s*:[^[\]\n]{0,200}\]/;
/** 스트리밍 중 아직 닫히지 않은 태그 앞부분 (`[`, `[감`, `[인물: 설월/당`) */
const PARTIAL_TAG = /^\[\s*(?:감(?:정\s*(?::[^[\]\n]{0,200})?)?|인(?:물\s*(?::[^[\]\n]{0,200})?)?)?$/;
/** 태그를 찾을 줄 수와 개수 상한 (백엔드와 같다) */
const MAX_TAG_LINES = 2;
const MAX_TAGS = 4;

/** 앞에서부터 연달아 붙은 태그들의 끝 위치. 태그가 없으면 0 */
function tagsEnd(content: string): number {
  let end = 0;
  let cursor = 0;
  let newlines = 0;
  for (let count = 0; count < MAX_TAGS; count++) {
    while (cursor < content.length && /\s/.test(content[cursor])) {
      if (content[cursor] === '\n') newlines++;
      cursor++;
    }
    if (newlines >= MAX_TAG_LINES) break;
    const match = TAG.exec(content.slice(cursor));
    if (!match) break;
    cursor += match[0].length;
    end = cursor;
  }
  return end;
}

/** 앞머리 회수에서 훑어볼 줄 수와 버릴 수 있는 글자 수 (백엔드와 같다) */
const MAX_PREAMBLE_LINES = 6;
const MAX_PREAMBLE_CHARS = 300;

/**
 * AI가 태그보다 먼저 다른 것을 뱉은 옛 메시지를 위한 안전망 (BUG-025).
 * 앞쪽 몇 줄 안에서 **줄 전체가 태그인 줄**을 찾으면 그 줄까지 버린다.
 * 서버가 T33 이후로 저장 시점에 같은 일을 하므로, 이건 그 전에 저장된 메시지용이다.
 */
function recoverAfterPreamble(content: string): string | null {
  const lines = content.split('\n');
  let consumed = 0;
  for (let i = 0; i < lines.length && i < MAX_PREAMBLE_LINES; i++) {
    const line = lines[i].trim();
    if (line) {
      const end = tagsEnd(line);
      // 줄 전체가 태그여야 한다. 태그 뒤에 글자가 남으면 본문이 섞인 줄이다
      if (end > 0 && !line.slice(end).trim()) {
        return lines.slice(i + 1).join('\n').replace(/^\s+/, '');
      }
    }
    consumed += lines[i].length + 1;
    if (consumed > MAX_PREAMBLE_CHARS) break;
  }
  return null;
}

/**
 * 앞쪽 감정·인물 태그를 숨긴다. 태그 뒤의 공백과 빈 줄도 함께 없앤다.
 * @param streaming 스트리밍 중이면 아직 닫히지 않은 태그 앞부분도 숨긴다
 */
export function hideEmotionTag(content: string, streaming = false): string {
  const end = tagsEnd(content);
  if (end > 0) return content.slice(end).replace(/^\s+/, '');
  if (streaming) return PARTIAL_TAG.test(content) ? '' : content;
  // 스트리밍이 끝난 뒤에만 앞머리를 회수한다. 스트리밍 중에 하면 글이 튄다
  return recoverAfterPreamble(content) ?? content;
}
