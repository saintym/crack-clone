import { useCallback, useState, type Dispatch, type SetStateAction } from 'react';
import { chatApi } from '../api/chat';
import type { Message } from '../types/chat';

/** 이어하기 때 화면에만 끼워 넣는 사용자 메시지 */
const CONTINUE_USER_MESSAGE = '계속 이어서 작성해주세요.';

/**
 * fetch 기반 SSE 스트림을 읽어 delta를 누적한다.
 * 백엔드 SSE는 여러 줄 데이터를 `data:` 줄 여러 개로 쪼개 보내므로 같은 이벤트 안의 줄은 개행으로 다시 잇는다.
 * @returns 누적한 delta 내용. delta가 비었으면 done 이벤트 데이터
 */
async function streamSSE(url: string, body: object, onDelta: (content: string) => void): Promise<string> {
  const token = localStorage.getItem('crack-token');
  const response = await fetch(url, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
    },
    body: JSON.stringify(body),
  });

  if (!response.ok) throw new Error('Stream failed');

  const reader = response.body?.getReader();
  const decoder = new TextDecoder();
  let fullResponse = '';
  let doneData = '';
  let buffer = '';

  while (reader) {
    const { done, value } = await reader.read();
    if (done) break;

    buffer += decoder.decode(value, { stream: true });
    const lines = buffer.split('\n');
    buffer = lines.pop() || '';

    let currentEvent = '';
    let deltaLineCount = 0;
    for (const line of lines) {
      if (line.startsWith('event:')) {
        currentEvent = line.slice(6).trim();
        deltaLineCount = 0;
      } else if (line.startsWith('data:')) {
        const data = line.slice(5);
        if (currentEvent === 'delta') {
          // SSE splits multiline data into multiple data: lines
          // Restore newlines between them
          if (deltaLineCount > 0) fullResponse += '\n';
          fullResponse += data;
          deltaLineCount++;
          onDelta(fullResponse);
        } else if (currentEvent === 'done') {
          // Accumulate done data (may span multiple data: lines)
          doneData += (doneData ? '\n' : '') + data;
        }
        // Don't reset currentEvent — SSE events can have multiple data: lines
      } else if (line.trim() === '') {
        // Empty line marks end of SSE event
        currentEvent = '';
        deltaLineCount = 0;
      }
    }
  }

  // Use accumulated delta content; fall back to done data if deltas were empty
  return fullResponse || doneData;
}

interface UseChatStreamOptions {
  storyId: number;
  /** 선택한 AI 프로바이더. 빈 문자열이면 서버 기본값 */
  provider: string;
  setMessages: Dispatch<SetStateAction<Message[]>>;
  /** 서버 히스토리로 메시지 목록을 다시 채운다 (재생성 실패 시 복구용) */
  reloadHistory: () => Promise<unknown>;
  /** 턴이 끝났을 때 호출 (스토리 목록 새로고침) */
  onTurnEnd: () => void;
}

/** 전송·재생성·이어하기 스트리밍과 complete 호출 */
export function useChatStream({ storyId, provider, setMessages, reloadHistory, onTurnEnd }: UseChatStreamOptions) {
  const [streaming, setStreaming] = useState(false);
  const [streamContent, setStreamContent] = useState('');

  /**
   * 스트림 하나를 끝까지 받아 메시지 목록에 반영하고 complete를 호출한다.
   * 호출 전에 streaming=true, streamContent=''로 만들어 두어야 한다.
   */
  const runStream = useCallback(async (
    url: string,
    message: string,
    append: (fullResponse: string) => Message[],
    errorLabel: string,
    onError?: () => void,
  ) => {
    try {
      const fullResponse = await streamSSE(url, {
        message,
        ...(provider ? { provider } : {}),
      }, setStreamContent);

      setStreamContent('');
      if (fullResponse.trim()) {
        setMessages((prev) => [...prev, ...append(fullResponse)]);
        await chatApi.complete(storyId, fullResponse);
      }
      onTurnEnd();
    } catch (err) {
      console.error(errorLabel, err);
      setStreamContent('');
      onError?.();
    } finally {
      setStreaming(false);
    }
  }, [storyId, provider, setMessages, onTurnEnd]);

  /** 사용자 메시지(상황서술 변환까지 끝난 것)를 보내고 응답을 스트리밍한다. */
  const send = useCallback(async (userMsg: string) => {
    if (streaming || !storyId) return;
    setMessages((prev) => [...prev, { role: 'user', content: userMsg }]);
    setStreaming(true);
    setStreamContent('');

    await runStream(chatApi.streamUrl(storyId), userMsg,
      (full) => [{ role: 'assistant', content: full }], 'Chat error:');
  }, [streaming, storyId, setMessages, runStream]);

  /** 마지막 AI 메시지를 지우고 다시 생성한다. 실패하면 서버 히스토리로 복구한다. */
  const regenerate = useCallback(async (messageIndex: number) => {
    if (streaming || !storyId) return;
    setStreaming(true);
    setStreamContent('');

    // Remove the assistant message from UI
    setMessages((prev) => prev.filter((_, i) => i !== messageIndex));

    await runStream(chatApi.regenerateUrl(storyId), '',
      (full) => [{ role: 'assistant', content: full }], 'Regenerate error:',
      // Reload history to restore consistent state
      () => { reloadHistory(); });
  }, [streaming, storyId, setMessages, runStream, reloadHistory]);

  /** 마지막 AI 메시지를 이어서 쓴다. */
  const continueStory = useCallback(async () => {
    if (streaming || !storyId) return;
    setStreaming(true);
    setStreamContent('');

    // Add the hidden "계속" user message + new assistant response
    await runStream(chatApi.continueUrl(storyId), '',
      (full) => [
        { role: 'user', content: CONTINUE_USER_MESSAGE },
        { role: 'assistant', content: full },
      ], 'Continue error:');
  }, [streaming, storyId, runStream]);

  return { streaming, streamContent, send, regenerate, continueStory };
}
