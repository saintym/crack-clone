import { useCallback, useEffect, useState } from 'react';
import { chatApi, errorMessage, isConflict } from '../api/chat';
import type { Message, MessagesResponse } from '../types/chat';

/** 서버가 생성 중일 때(`generating`, 409) 목록을 다시 확인하는 간격 */
const BUSY_POLL_MS = 2500;

export const BUSY_NOTICE = '응답을 생성하는 중입니다. 끝나면 자동으로 풀립니다.';

const EMPTY: Message[] = [];

/** 불러온 스토리 하나의 상태. `storyId`가 현재 스토리와 다르면 버린 것으로 본다 */
interface LoadedState {
  storyId: number;
  messages: Message[];
  /** 서버가 이 스토리에서 응답을 생성 중이다. 입력과 메시지 조작을 잠근다 */
  busy: boolean;
}

const fromResponse = (storyId: number, data: MessagesResponse): LoadedState => ({
  storyId,
  messages: data.messages,
  busy: data.story.generating,
});

/** id가 같으면 교체하고, 없으면 seq 순서에 맞춰 넣는다 */
function upsert(messages: Message[], message: Message): Message[] {
  if (messages.some((m) => m.id === message.id)) {
    return messages.map((m) => (m.id === message.id ? message : m));
  }
  return [...messages, message].sort((a, b) => a.seq - b.seq);
}

/**
 * 메시지 목록 상태 (`GET /messages`): 로드, 스트림 결과 반영, 후보 선택·수정·삭제, 생성 중 잠금.
 * - 서버가 생성 중이면(`story.generating` 또는 409) `busy`로 잠그고, 끝날 때까지 목록을 주기적으로 다시 받는다.
 * - 상태에 storyId를 함께 두어, 스토리를 옮긴 뒤 늦게 온 응답이 다른 스토리 목록에 섞이지 않게 한다.
 * @param onChanged 턴 수가 바뀌었을 수 있을 때 호출 (스토리 목록 새로고침)
 */
export function useMessages(storyId: number, onChanged: () => void) {
  const [state, setState] = useState<LoadedState | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [pollTick, setPollTick] = useState(0);

  const current = state?.storyId === storyId ? state : null;
  const messages = current?.messages ?? EMPTY;
  const busy = current?.busy ?? false;
  const loaded = current !== null;

  /** 해당 스토리의 상태일 때만 고친다 */
  const update = useCallback((sid: number, fn: (s: LoadedState) => LoadedState) => {
    setState((prev) => (prev && prev.storyId === sid ? fn(prev) : prev));
  }, []);

  useEffect(() => {
    if (!storyId) return;
    let cancelled = false;
    chatApi.list(storyId)
      .then(({ data }) => {
        if (!cancelled) setState(fromResponse(storyId, data));
      })
      .catch((err) => {
        if (!cancelled) setError(errorMessage(err, '대화를 불러오지 못했습니다'));
      });
    return () => { cancelled = true; };
  }, [storyId]);

  // 서버가 생성 중인 동안 목록을 다시 받는다. 끝나면 새 응답까지 들어온 목록으로 바뀌고 잠금이 풀린다.
  useEffect(() => {
    if (!busy || !storyId) return;
    let cancelled = false;
    const timer = window.setTimeout(() => {
      chatApi.list(storyId)
        .then(({ data }) => {
          if (cancelled) return;
          setState(fromResponse(storyId, data));
          if (data.story.generating) {
            setPollTick((t) => t + 1);
          } else {
            setError((e) => (e === BUSY_NOTICE ? null : e));
            onChanged();
          }
        })
        .catch(() => {
          if (!cancelled) setPollTick((t) => t + 1);
        });
    }, BUSY_POLL_MS);
    return () => {
      cancelled = true;
      window.clearTimeout(timer);
    };
  }, [busy, pollTick, storyId, onChanged]);

  /** 서버 목록으로 다시 채운다 (스트림이 done/error 없이 끊겼을 때). 생성 중이면 잠긴다 */
  const reload = useCallback((sid: number) => {
    chatApi.list(sid)
      .then(({ data }) => update(sid, () => fromResponse(sid, data)))
      .catch((err) => setError(errorMessage(err, '대화를 불러오지 못했습니다')));
  }, [update]);

  /** 409를 받았을 때: 잠그고 생성이 끝나기를 기다린다 */
  const markBusy = useCallback((sid: number) => {
    update(sid, (s) => ({ ...s, busy: true }));
    setError(BUSY_NOTICE);
  }, [update]);

  /** 서버가 돌려준 메시지(스트림의 user/done, 수정 결과)로 교체하거나 추가한다 */
  const applyMessage = useCallback((sid: number, message: Message) => {
    update(sid, (s) => ({ ...s, messages: upsert(s.messages, message) }));
  }, [update]);

  const handleError = useCallback((sid: number, err: unknown, fallback: string) => {
    if (isConflict(err)) markBusy(sid);
    else setError(errorMessage(err, fallback));
  }, [markBusy]);

  /** @returns 저장했으면 true. 실패하면 편집기를 닫지 않는다 */
  const editMessage = useCallback((messageId: number, content: string): Promise<boolean> => {
    const sid = storyId;
    return chatApi.edit(sid, messageId, content)
      .then(({ data }) => {
        applyMessage(sid, data);
        return true;
      })
      .catch((err) => {
        handleError(sid, err, '메시지를 수정하지 못했습니다');
        return false;
      });
  }, [storyId, applyMessage, handleError]);

  /** 가장 최근 AI 메시지의 후보를 고른다 */
  const selectVariant = useCallback((messageId: number, index: number) => {
    const sid = storyId;
    chatApi.selectVariant(sid, messageId, index)
      .then(({ data }) => applyMessage(sid, data))
      .catch((err) => handleError(sid, err, '후보를 바꾸지 못했습니다'));
  }, [storyId, applyMessage, handleError]);

  /** 이 메시지부터 끝까지 삭제한다 */
  const deleteFrom = useCallback((messageId: number) => {
    const sid = storyId;
    chatApi.truncateFrom(sid, messageId)
      .then(() => {
        update(sid, (s) => {
          const target = s.messages.find((m) => m.id === messageId);
          if (!target) return s;
          return { ...s, messages: s.messages.filter((m) => m.seq < target.seq) };
        });
        onChanged();
      })
      .catch((err) => handleError(sid, err, '메시지를 삭제하지 못했습니다'));
  }, [storyId, update, onChanged, handleError]);

  const clearError = useCallback(() => setError(null), []);

  return {
    messages, loaded, busy, error, clearError,
    reload, markBusy, applyMessage, editMessage, selectVariant, deleteFrom,
  };
}
