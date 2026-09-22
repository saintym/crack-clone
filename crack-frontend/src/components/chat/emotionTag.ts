/** 감정 태그 한 줄 (DESIGN.md §5.3, 백엔드 `EmotionTagFilter`와 같은 규칙) */
const TAG_LINE = /^\s*\[\s*감정\s*:\s*(.+?)\]\s*$/;
/** 스트리밍 중 아직 닫히지 않은 태그 앞부분 (`[`, `[감`, `[감정: 경계`) */
const PARTIAL_TAG = /^\s*\[\s*(감\s*(정\s*(:[^\]\n]*)?)?)?$/;

/**
 * 방어용 감정 태그 숨김. 서버가 이미 떼어 내고 보내지만, 첫 줄이 `[감정: …]`이면 한 번 더 숨긴다.
 * 첫 줄만 본다(본문 중간의 대괄호 문장은 건드리지 않는다).
 * @param streaming 스트리밍 중이면 아직 줄바꿈이 오지 않은 태그 앞부분도 숨긴다
 */
export function hideEmotionTag(content: string, streaming = false): string {
  const newline = content.indexOf('\n');
  const firstLine = newline < 0 ? content : content.slice(0, newline);
  if (TAG_LINE.test(firstLine)) {
    return newline < 0 ? '' : content.slice(newline + 1).replace(/^(?:[ \t]*\r?\n)+/, '');
  }
  if (streaming && newline < 0 && PARTIAL_TAG.test(firstLine)) return '';
  return content;
}
