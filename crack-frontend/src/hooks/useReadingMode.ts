import { useCallback, useState } from 'react';

/** 말풍선(대화형) / 소설형 (DESIGN.md §10.1, D35) */
export type ReadingMode = 'bubble' | 'novel';

const KEY = 'crack.readingMode';
const DEFAULT: ReadingMode = 'novel';

/** 저장된 값을 읽는다. 시크릿 창이나 저장이 막힌 브라우저에서는 기본값을 쓴다. */
function load(): ReadingMode {
  try {
    return localStorage.getItem(KEY) === 'bubble' ? 'bubble' : DEFAULT;
  } catch {
    return DEFAULT;
  }
}

/**
 * 읽기 모드. 브라우저마다 따로 기억한다(서버에 올리지 않는다).
 * 기본은 소설형이다 — 이 앱은 대화가 아니라 이야기를 읽는 화면이기 때문이다.
 */
export function useReadingMode() {
  const [mode, setMode] = useState<ReadingMode>(load);

  const toggle = useCallback(() => {
    setMode((cur) => {
      const next: ReadingMode = cur === 'novel' ? 'bubble' : 'novel';
      try {
        localStorage.setItem(KEY, next);
      } catch {
        // 저장하지 못해도 이번 세션 동안은 바뀐 모드로 본다
      }
      return next;
    });
  }, []);

  return { mode, toggle };
}
