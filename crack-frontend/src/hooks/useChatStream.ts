import { useCallback, useEffect, useRef, useState } from 'react';
import {
  ChatApiError, chatApi, isAbort, isConflict,
  type StreamHandlers, type StreamResult,
} from '../api/chat';
import type { Message } from '../types/chat';

export type StreamMode = 'send' | 'regenerate' | 'continue';

/** 진행 중인 스트림. 화면(MessageList)은 이것으로 스트리밍 말풍선 위치를 정한다 */
export interface StreamState {
  storyId: number;
  mode: StreamMode;
  /** 재생성 대상 AI 메시지. 스트리밍하는 동안 이 메시지 자리에 새 응답을 보인다. 실패하면 원래 답변이 다시 보인다 */
  targetId?: number;
  /** 전송만: 서버가 `user` 이벤트로 저장을 알리기 전까지 보일 유저 메시지 */
  pendingUser?: string;
  /** 지금까지 받은 delta */
  content: string;
}

interface UseChatStreamOptions {
  storyId: number;
  /** 선택한 AI 프로바이더. 빈 문자열이면 서버 기본값 */
  provider: string;
  /** 서버가 저장한 메시지(`user`, `done`)를 목록에 반영한다 */
  onMessage: (storyId: number, message: Message) => void;
  /** 409: 서버가 이미 생성 중이다 */
  onConflict: (storyId: number) => void;
  /** done/error 없이 연결이 끊겼다. 서버는 생성·저장을 계속하므로 목록을 다시 받는다 */
  onInterrupted: (storyId: number) => void;
  /** 턴 수가 바뀌었을 수 있다 (스토리 목록 새로고침) */
  onTurnEnd: () => void;
}

/**
 * 전송·재생성·이어쓰기 SSE (DESIGN.md §5.2). 서버가 저장하므로 클라이언트는 `done`의 메시지로 목록을 교체하기만 한다.
 * 스토리를 옮기거나 화면을 떠나면 읽기만 멈춘다(서버는 생성·저장을 끝까지 한다).
 */
export function useChatStream({ storyId, provider, onMessage, onConflict, onInterrupted, onTurnEnd }: UseChatStreamOptions) {
  const [stream, setStream] = useState<StreamState | null>(null);
  const [error, setError] = useState<string | null>(null);
  const sessionRef = useRef<AbortController | null>(null);
  const runningRef = useRef(false);

  // 스토리마다 중단 신호 하나. 스토리가 바뀌거나 화면을 떠나면 진행 중인 스트림 읽기를 멈춘다.
  useEffect(() => {
    const controller = new AbortController();
    sessionRef.current = controller;
    return () => controller.abort();
  }, [storyId]);

  /**
   * 스트림 하나를 끝까지 받는다.
   * @returns 전송에서 유저 메시지가 저장되었는지 (입력창 내용 복구 판단용)
   */
  const run = useCallback(async (
    init: Omit<StreamState, 'storyId' | 'content'>,
    call: (handlers: StreamHandlers, signal?: AbortSignal) => Promise<StreamResult>,
  ): Promise<boolean> => {
    if (runningRef.current || !storyId) return false;
    runningRef.current = true;
    const sid = storyId;
    let userSaved = false;
    setError(null);
    setStream({ ...init, storyId: sid, content: '' });

    try {
      const result = await call({
        onUser: (message) => {
          userSaved = true;
          onMessage(sid, message);
          setStream((s) => (s ? { ...s, pendingUser: undefined } : s));
        },
        onDelta: (text) => setStream((s) => (s ? { ...s, content: s.content + text } : s)),
      }, sessionRef.current?.signal);

      if (result.type === 'done') {
        onMessage(sid, result.message);
        onTurnEnd();
      } else if (result.type === 'error') {
        setError(`응답을 생성하지 못했습니다: ${result.message}`);
        if (userSaved) onTurnEnd();
      } else {
        onInterrupted(sid);
      }
    } catch (err) {
      if (isAbort(err)) {
        // 스토리를 옮겼거나 화면을 떠났다. 서버는 계속 생성한다.
      } else if (isConflict(err)) {
        onConflict(sid);
      } else if (err instanceof ChatApiError) {
        setError(err.message);
      } else {
        console.error('Stream error:', err);
        setError('연결이 끊겼습니다. 서버에서 생성이 끝나면 목록에 반영됩니다.');
        onInterrupted(sid);
      }
    } finally {
      runningRef.current = false;
      setStream(null);
    }
    return userSaved;
  }, [storyId, onMessage, onConflict, onInterrupted, onTurnEnd]);

  const providerBody = useCallback(() => (provider ? { provider } : {}), [provider]);

  /**
   * 유저 메시지(상황서술 변환까지 끝난 것)를 보내고 응답을 받는다.
   * @param command 사용자 정의 명령 이름(T18). `content`는 `/이름 인자` 그대로 보낸다
   * @returns 유저 메시지가 저장되었으면 true. false면 입력창에 내용을 되돌린다
   */
  const send = useCallback((content: string, command?: string) =>
    run({ mode: 'send', pendingUser: content },
      (handlers, signal) => chatApi.send(storyId, { content, ...(command ? { command } : {}), ...providerBody() }, handlers, signal)),
  [run, storyId, providerBody]);

  /**
   * 대화의 마지막 메시지를 대상으로 재생성한다.
   * AI 메시지면 후보를 추가하고, 유저 메시지(응답 실패)면 그 턴의 첫 응답을 만든다. 실패하면 원래 답변은 그대로다.
   */
  const regenerate = useCallback((target: Message, instruction?: string) => {
    const trimmed = instruction?.trim();
    return run({ mode: 'regenerate', targetId: target.role === 'ASSISTANT' ? target.id : undefined },
      (handlers, signal) => chatApi.regenerate(storyId, {
        ...providerBody(),
        messageId: target.id,
        ...(trimmed ? { instruction: trimmed } : {}),
      }, handlers, signal));
  }, [run, storyId, providerBody]);

  /** 유저 메시지 없이 이어쓴다 */
  const continueStory = useCallback(() =>
    run({ mode: 'continue' },
      (handlers, signal) => chatApi.continueStory(storyId, providerBody(), handlers, signal)),
  [run, storyId, providerBody]);

  const clearError = useCallback(() => setError(null), []);
  const current = stream?.storyId === storyId ? stream : null;

  return {
    stream: current,
    streaming: current !== null,
    error, clearError,
    send, regenerate, continueStory,
  };
}
