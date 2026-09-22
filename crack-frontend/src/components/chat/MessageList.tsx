import { useEffect, useRef, useState } from 'react';
import type { Message } from '../../types/chat';
import ChatBubble from './ChatBubble';
import MessageMenu from './MessageMenu';

interface MessageListProps {
  messages: Message[];
  streaming: boolean;
  streamContent: string;
  onRegenerate: (messageIndex: number) => void;
  onContinue: () => void;
  onBranch: (messageIndex: number) => void;
  /** @returns 요청을 시도했으면 true. true일 때만 편집 상태를 닫는다 */
  onEditSave: (messageIndex: number, content: string) => Promise<boolean>;
  onDelete: (messageIndex: number) => void;
}

/** 메시지 목록, 스트리밍 중인 응답, AI 메시지 인라인 편집 */
export default function MessageList({
  messages, streaming, streamContent, onRegenerate, onContinue, onBranch, onEditSave, onDelete,
}: MessageListProps) {
  const messagesEndRef = useRef<HTMLDivElement>(null);
  const [menuOpenIndex, setMenuOpenIndex] = useState<number | null>(null);
  const [editingIndex, setEditingIndex] = useState<number | null>(null);
  const [editContent, setEditContent] = useState('');

  // Auto-scroll
  useEffect(() => {
    messagesEndRef.current?.scrollIntoView({ behavior: 'smooth' });
  }, [messages, streamContent]);

  const handleEditStart = (messageIndex: number) => {
    setMenuOpenIndex(null);
    setEditingIndex(messageIndex);
    setEditContent(messages[messageIndex].content);
  };

  const handleEditSave = async () => {
    if (editingIndex === null) return;
    if (await onEditSave(editingIndex, editContent)) {
      setEditingIndex(null);
      setEditContent('');
    }
  };

  return (
    <div className="flex-1 overflow-y-auto px-4 py-5 space-y-5 bg-bg-chat">
      {messages.length === 0 && !streaming && (
        <div className="flex flex-col items-center justify-center h-full text-text-muted">
          <svg width="48" height="48" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.2" className="mb-3 opacity-30">
            <path d="M21 15a2 2 0 01-2 2H7l-4 4V5a2 2 0 012-2h14a2 2 0 012 2z" />
          </svg>
          <p className="text-sm">대화를 시작하세요</p>
        </div>
      )}

      {messages.map((msg, i) => (
        <div key={i}>
          {editingIndex === i ? (
            <div className="flex justify-start pr-10">
              <div className="max-w-[85%] w-full">
                <textarea
                  value={editContent}
                  onChange={(e) => setEditContent(e.target.value)}
                  className="w-full p-4 bg-surface border border-accent/60 rounded-2xl text-text-primary text-[15px] font-mono resize-none focus:outline-none leading-relaxed min-h-[120px]"
                />
                <div className="flex gap-2 mt-2">
                  <button
                    onClick={() => { setEditingIndex(null); setEditContent(''); }}
                    className="px-4 py-2 text-sm bg-surface hover:bg-surface-hover text-text-secondary rounded-xl transition-all"
                  >
                    취소
                  </button>
                  <button
                    onClick={handleEditSave}
                    className="px-4 py-2 text-sm bg-accent hover:bg-accent-hover text-white rounded-xl font-medium transition-all"
                  >
                    저장
                  </button>
                </div>
              </div>
            </div>
          ) : (
            <ChatBubble role={msg.role} content={msg.content} />
          )}

          {/* Message actions for assistant messages */}
          {msg.role === 'assistant' && editingIndex !== i && !streaming && (
            <MessageMenu
              isLast={i === messages.length - 1}
              open={menuOpenIndex === i}
              onToggle={() => setMenuOpenIndex(menuOpenIndex === i ? null : i)}
              onClose={() => setMenuOpenIndex(null)}
              onRegenerate={() => { setMenuOpenIndex(null); onRegenerate(i); }}
              onContinue={onContinue}
              onBranch={() => { setMenuOpenIndex(null); onBranch(i); }}
              onEdit={() => handleEditStart(i)}
              onDelete={() => { setMenuOpenIndex(null); onDelete(i); }}
            />
          )}
        </div>
      ))}

      {streaming && streamContent && (
        <ChatBubble role="assistant" content={streamContent} isStreaming />
      )}

      <div ref={messagesEndRef} />
    </div>
  );
}
