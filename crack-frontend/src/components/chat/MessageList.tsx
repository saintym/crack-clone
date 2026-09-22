import { useEffect, useRef, useState } from 'react';
import type { Message } from '../../types/chat';
import type { StreamState } from '../../hooks/useChatStream';
import ChatBubble from './ChatBubble';
import MessageMenu from './MessageMenu';

interface MessageListProps {
  messages: Message[];
  /** 진행 중인 스트림 (없으면 null) */
  stream: StreamState | null;
  /** 스트리밍 중이거나 서버가 생성 중이면 true. 메시지 조작 버튼을 숨긴다 */
  locked: boolean;
  onSelectVariant: (message: Message, index: number) => void;
  /** 대화의 마지막 메시지 재생성 (AI면 후보 추가, 유저면 첫 응답) */
  onRegenerate: (message: Message, instruction?: string) => void;
  onContinue: () => void;
  onBranch: (message: Message) => void;
  /** @returns 저장했으면 true. true일 때만 편집기를 닫는다 */
  onEditSave: (message: Message, content: string) => Promise<boolean>;
  /** 이 메시지부터 끝까지 삭제 */
  onDelete: (message: Message) => void;
}

const isComposing = (e: React.KeyboardEvent) => e.nativeEvent.isComposing || e.keyCode === 229;

/** 메시지 목록, 스트리밍 중인 응답, 인라인 편집기와 재생성 지시 입력 */
export default function MessageList({
  messages, stream, locked, onSelectVariant, onRegenerate, onContinue, onBranch, onEditSave, onDelete,
}: MessageListProps) {
  const messagesEndRef = useRef<HTMLDivElement>(null);
  const [menuOpenId, setMenuOpenId] = useState<number | null>(null);
  const [editing, setEditing] = useState<{ id: number; content: string; saving: boolean } | null>(null);
  const [instructing, setInstructing] = useState<{ id: number; text: string } | null>(null);

  // 메시지가 늘거나 스트림이 자랄 때만 아래로 내린다 (과거 메시지 수정·후보 넘기기에는 움직이지 않는다)
  const messageCount = messages.length;
  const streamText = stream?.content;
  const pendingUser = stream?.pendingUser;
  const streamActive = stream !== null;
  useEffect(() => {
    messagesEndRef.current?.scrollIntoView({ behavior: 'smooth' });
  }, [messageCount, streamText, pendingUser, streamActive]);

  const last = messages[messages.length - 1];
  const latestAssistant = [...messages].reverse().find((m) => m.role === 'ASSISTANT');

  const startEdit = (message: Message) => {
    setInstructing(null);
    setEditing({ id: message.id, content: message.content, saving: false });
  };

  const saveEdit = (message: Message) => {
    if (!editing || editing.saving) return;
    if (editing.content === message.content) {
      setEditing(null);
      return;
    }
    if (!editing.content.trim()) return;
    setEditing({ ...editing, saving: true });
    onEditSave(message, editing.content).then((saved) => {
      setEditing((cur) => (cur && cur.id === message.id ? (saved ? null : { ...cur, saving: false }) : cur));
    });
  };

  const submitInstruction = (message: Message) => {
    if (!instructing) return;
    const text = instructing.text;
    setInstructing(null);
    onRegenerate(message, text);
  };

  const streamingInPlace = stream?.mode === 'regenerate' && stream.targetId !== undefined;

  return (
    <div className="flex-1 overflow-y-auto px-4 py-5 space-y-5 bg-bg-chat">
      {messages.length === 0 && !stream && (
        <div className="flex flex-col items-center justify-center h-full text-text-muted">
          <svg width="48" height="48" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.2" className="mb-3 opacity-30">
            <path d="M21 15a2 2 0 01-2 2H7l-4 4V5a2 2 0 012-2h14a2 2 0 012 2z" />
          </svg>
          <p className="text-sm">대화를 시작하세요</p>
        </div>
      )}

      {messages.map((msg) => {
        const isUser = msg.role === 'USER';
        const isLast = msg.id === last?.id;

        // 재생성 중에는 원래 답변 자리에 새 응답을 보인다. 실패하면 스트림이 사라지고 원래 답변이 다시 보인다.
        if (streamingInPlace && stream?.targetId === msg.id) {
          return <ChatBubble key={msg.id} role="ASSISTANT" content={stream.content} isStreaming />;
        }

        const isEditing = editing?.id === msg.id;
        return (
          <div key={msg.id}>
            {isEditing ? (
              <div className={`flex ${isUser ? 'justify-end pl-10' : 'justify-start pr-10'}`}>
                <div className="max-w-[85%] w-full">
                  <textarea
                    value={editing.content}
                    onChange={(e) => setEditing({ ...editing, content: e.target.value })}
                    onKeyDown={(e) => {
                      if (e.key === 'Escape') setEditing(null);
                    }}
                    autoFocus
                    className="w-full p-4 bg-surface border border-accent/60 rounded-2xl text-text-primary text-[15px] resize-y focus:outline-none leading-relaxed min-h-[120px]"
                  />
                  <div className={`flex gap-2 mt-2 ${isUser ? 'justify-end' : ''}`}>
                    <button
                      onClick={() => setEditing(null)}
                      className="px-4 py-2 text-sm bg-surface hover:bg-surface-hover text-text-secondary rounded-xl transition-all"
                    >
                      취소
                    </button>
                    <button
                      onClick={() => saveEdit(msg)}
                      disabled={editing.saving || !editing.content.trim() || locked}
                      className="px-4 py-2 text-sm bg-accent hover:bg-accent-hover disabled:opacity-40 text-white rounded-xl font-medium transition-all"
                    >
                      {editing.saving ? '저장 중…' : '저장'}
                    </button>
                  </div>
                </div>
              </div>
            ) : (
              <ChatBubble role={msg.role} content={msg.content} />
            )}

            {!isEditing && (
              <div className={`flex items-center gap-2 mt-1.5 px-1 ${isUser ? 'justify-end' : ''}`}>
                {!locked && (
                  <MessageMenu
                    isUser={isUser}
                    variants={msg.id === latestAssistant?.id && msg.variantIndex !== null ? {
                      index: msg.variantIndex,
                      count: msg.variantCount,
                      onSelect: (index) => onSelectVariant(msg, index),
                    } : undefined}
                    canRegenerate={isLast && !isUser && msg.kind !== 'PROLOGUE'}
                    canContinue={isLast && !isUser}
                    canRetry={isLast && isUser}
                    open={menuOpenId === msg.id}
                    onToggle={() => setMenuOpenId(msg.id)}
                    onClose={() => setMenuOpenId(null)}
                    onRegenerate={() => onRegenerate(msg)}
                    onRegenerateWithInstruction={() => {
                      setEditing(null);
                      setInstructing({ id: msg.id, text: '' });
                    }}
                    onContinue={onContinue}
                    onBranch={() => onBranch(msg)}
                    onEdit={() => startEdit(msg)}
                    onDelete={() => onDelete(msg)}
                  />
                )}
                {msg.edited && <span className="text-[11px] text-text-muted">수정됨</span>}
              </div>
            )}

            {instructing?.id === msg.id && !locked && (
              <div className="flex justify-start pr-10 mt-2">
                <div className="max-w-[85%] w-full">
                  <textarea
                    value={instructing.text}
                    onChange={(e) => setInstructing({ ...instructing, text: e.target.value })}
                    onKeyDown={(e) => {
                      if (e.key === 'Escape') setInstructing(null);
                      if (e.key === 'Enter' && !e.shiftKey && !isComposing(e)) {
                        e.preventDefault();
                        submitInstruction(msg);
                      }
                    }}
                    placeholder="이번 재생성에만 적용할 지시 (예: 더 짧게, 대사 위주로)"
                    rows={2}
                    autoFocus
                    className="w-full px-4 py-3 bg-surface border border-accent/60 rounded-2xl text-text-primary placeholder-text-muted text-[14px] resize-none focus:outline-none"
                  />
                  <div className="flex gap-2 mt-2">
                    <button
                      onClick={() => setInstructing(null)}
                      className="px-4 py-2 text-sm bg-surface hover:bg-surface-hover text-text-secondary rounded-xl transition-all"
                    >
                      취소
                    </button>
                    <button
                      onClick={() => submitInstruction(msg)}
                      className="px-4 py-2 text-sm bg-accent hover:bg-accent-hover text-white rounded-xl font-medium transition-all"
                    >
                      재생성
                    </button>
                  </div>
                </div>
              </div>
            )}
          </div>
        );
      })}

      {stream?.pendingUser && <ChatBubble role="USER" content={stream.pendingUser} />}

      {stream && !streamingInPlace && (
        <ChatBubble role="ASSISTANT" content={stream.content} isStreaming />
      )}

      <div ref={messagesEndRef} />
    </div>
  );
}
