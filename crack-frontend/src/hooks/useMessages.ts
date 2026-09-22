import { useCallback, useEffect, useState } from 'react';
import { chatApi } from '../api/chat';
import type { Message, MessageRole } from '../types/chat';

/** 백엔드가 돌려주는 대화 마크다운(`## USER` / `## ASSISTANT` 섹션)을 메시지 목록으로 바꾼다. */
export function parseHistory(raw: string): Message[] {
  const msgs: Message[] = [];
  const sections = raw.split(/^## (USER|ASSISTANT)$/m);
  for (let i = 1; i < sections.length; i += 2) {
    const role = sections[i].toLowerCase() as MessageRole;
    const content = sections[i + 1]?.trim();
    if (content) msgs.push({ role, content });
  }
  return msgs;
}

/**
 * 메시지 목록 상태: 히스토리 로드와 파싱, AI 메시지 수정·삭제.
 * @param onChanged 삭제처럼 턴 수가 바뀌었을 때 호출 (스토리 목록 새로고침)
 */
export function useMessages(storyId: number, onChanged: () => void) {
  const [messages, setMessages] = useState<Message[]>([]);

  /** 서버 히스토리로 목록을 다시 채운다. 실패는 호출한 쪽이 처리한다. */
  const reloadHistory = useCallback(
    () =>
      chatApi.getHistory(storyId).then(({ data }) => {
        const raw = typeof data === 'string' ? data : data.content;
        setMessages(parseHistory(raw));
      }),
    [storyId],
  );

  useEffect(() => {
    if (!storyId) return;
    reloadHistory().catch(console.error);
  }, [storyId, reloadHistory]);

  /** @returns 요청을 시도했으면 true (성공 여부와 무관), storyId가 없어 건너뛰었으면 false */
  const editMessage = useCallback(async (messageIndex: number, content: string): Promise<boolean> => {
    if (!storyId) return false;
    try {
      await chatApi.editMessage(storyId, messageIndex, content);
      setMessages((prev) =>
        prev.map((msg, i) => i === messageIndex ? { ...msg, content } : msg)
      );
    } catch (err) {
      console.error('Edit error:', err);
    }
    return true;
  }, [storyId]);

  /** 해당 메시지부터 끝까지 잘라낸다. */
  const deleteMessage = useCallback(async (messageIndex: number) => {
    if (!storyId) return;
    try {
      await chatApi.deleteMessage(storyId, messageIndex);
      setMessages((prev) => prev.slice(0, messageIndex));
      onChanged();
    } catch (err) {
      console.error('Delete error:', err);
    }
  }, [storyId, onChanged]);

  return { messages, setMessages, reloadHistory, editMessage, deleteMessage };
}
