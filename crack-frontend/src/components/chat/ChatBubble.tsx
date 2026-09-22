import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import remarkBreaks from 'remark-breaks';
import type { MessageRole } from '../../types/chat';
import { hideEmotionTag } from './emotionTag';

interface ChatBubbleProps {
  role: MessageRole;
  content: string;
  isStreaming?: boolean;
}

/** 메시지 말풍선. 마크다운을 렌더링하고, 방어용으로 첫 줄 감정 태그를 숨긴다(§5.3) */
export default function ChatBubble({ role, content, isStreaming }: ChatBubbleProps) {
  const isUser = role === 'USER';
  const displayContent = hideEmotionTag(content, isStreaming);

  return (
    <div className={`flex ${isUser ? 'justify-end pl-10' : 'justify-start pr-10'}`}>
      <div
        className={`max-w-[85%] rounded-2xl px-4 py-3.5 ${
          isUser
            ? 'bg-user-bubble text-text-primary rounded-br-sm'
            : 'bg-ai-bubble border border-border/40 text-text-primary rounded-bl-sm'
        }`}
      >
        {isStreaming && !displayContent ? (
          <div className="flex items-center gap-1 h-[26px]" aria-label="응답 생성 중">
            <span className="w-1.5 h-1.5 rounded-full bg-text-muted animate-pulse" />
            <span className="w-1.5 h-1.5 rounded-full bg-text-muted animate-pulse [animation-delay:150ms]" />
            <span className="w-1.5 h-1.5 rounded-full bg-text-muted animate-pulse [animation-delay:300ms]" />
          </div>
        ) : (
          <div className="chat-markdown text-[15px] leading-[1.7] break-words">
            <ReactMarkdown
              remarkPlugins={[remarkGfm, remarkBreaks]}
              components={{
                img: ({ src, alt }) => (
                  <img
                    src={src}
                    alt={alt || ''}
                    className="max-w-full rounded-xl my-2 border border-border/30"
                    loading="lazy"
                  />
                ),
                a: ({ href, children }) => (
                  <a
                    href={href}
                    target="_blank"
                    rel="noopener noreferrer"
                    className="text-accent hover:text-accent-hover underline"
                  >
                    {children}
                  </a>
                ),
              }}
            >
              {displayContent}
            </ReactMarkdown>
            {isStreaming && (
              <span className="inline-block w-0.5 h-4 bg-accent ml-0.5 animate-pulse align-middle" />
            )}
          </div>
        )}
      </div>
    </div>
  );
}
