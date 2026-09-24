import { useContext, useState } from 'react';
import ReactMarkdown, { type Components } from 'react-markdown';
import remarkGfm from 'remark-gfm';
import remarkBreaks from 'remark-breaks';
import type { MessageRole } from '../../types/chat';
import { isSafeImageUrl, type ImageEntry } from '../../api/images';
import { hideEmotionTag } from './emotionTag';
import { ImageCatalogContext, resolveCharacterImage, splitImageTags } from './imageTags';

interface ChatBubbleProps {
  role: MessageRole;
  content: string;
  /** 인물 이미지 자동 선택용 (DESIGN.md §8.5). 스트리밍 중에는 아직 모른다 */
  speaker?: string | null;
  speakerVariant?: string | null;
  isStreaming?: boolean;
}

const markdownComponents: Components = {
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
};

function Markdown({ text }: { text: string }) {
  return (
    <ReactMarkdown remarkPlugins={[remarkGfm, remarkBreaks]} components={markdownComponents}>
      {text}
    </ReactMarkdown>
  );
}

/** 카탈로그 이미지. 불러오지 못하면 숨긴다 */
function CatalogImage({ entry }: { entry: ImageEntry }) {
  const [failedUrl, setFailedUrl] = useState<string | null>(null);
  if (failedUrl === entry.url || !isSafeImageUrl(entry.url)) return null;
  return (
    <img
      src={entry.url}
      alt={entry.description || entry.tag}
      loading="lazy"
      referrerPolicy="no-referrer"
      onError={() => setFailedUrl(entry.url)}
      className="block max-w-full max-h-[480px] rounded-xl my-2 border border-border/30"
    />
  );
}

/**
 * 메시지 말풍선. 마크다운을 렌더링하고, 방어용으로 앞쪽 감정·인물 태그를 숨긴다(§5.3).
 * AI 말풍선의 `{{img:태그}}` 줄은 카탈로그 이미지로 바꾼다(§8.5). 모르는 태그와 받기 전의 태그는 숨긴다.
 *
 * 본문에 카탈로그에 있는 `{{img:…}}`가 없으면 `speaker`로 인물 이미지를 골라 맨 위에 보여 준다(§8.5, D31).
 * 본문에 직접 넣은 이미지가 있으면 그게 우선이라 자동 선택을 하지 않는다.
 */
export default function ChatBubble({ role, content, speaker, speakerVariant, isStreaming }: ChatBubbleProps) {
  const isUser = role === 'USER';
  const catalog = useContext(ImageCatalogContext);
  const displayContent = hideEmotionTag(content, isStreaming);
  const segments = isUser ? null : splitImageTags(displayContent, isStreaming);
  const empty = segments ? segments.length === 0 : !displayContent;
  const hasBodyImage = segments?.some((s) => s.type === 'image' && catalog?.has(s.tag)) ?? false;
  const autoImage = hasBodyImage ? null : resolveCharacterImage(catalog, speaker, speakerVariant);

  return (
    <div className={`flex ${isUser ? 'justify-end pl-10' : 'justify-start pr-10'}`}>
      <div
        className={`max-w-[85%] rounded-2xl px-4 py-3.5 ${
          isUser
            ? 'bg-user-bubble text-text-primary rounded-br-sm'
            : 'bg-ai-bubble border border-border/40 text-text-primary rounded-bl-sm'
        }`}
      >
        {isStreaming && empty ? (
          <div className="flex items-center gap-1 h-[26px]" aria-label="응답 생성 중">
            <span className="w-1.5 h-1.5 rounded-full bg-text-muted animate-pulse" />
            <span className="w-1.5 h-1.5 rounded-full bg-text-muted animate-pulse [animation-delay:150ms]" />
            <span className="w-1.5 h-1.5 rounded-full bg-text-muted animate-pulse [animation-delay:300ms]" />
          </div>
        ) : (
          <div className="chat-markdown text-[15px] leading-[1.7] break-words">
            {autoImage && <CatalogImage entry={autoImage} />}
            {segments === null ? (
              <Markdown text={displayContent} />
            ) : (
              segments.map((segment, i) => {
                if (segment.type === 'text') return <Markdown key={i} text={segment.text} />;
                const entry = catalog?.get(segment.tag);
                return entry ? <CatalogImage key={i} entry={entry} /> : null;
              })
            )}
            {isStreaming && (
              <span className="inline-block w-0.5 h-4 bg-accent ml-0.5 animate-pulse align-middle" />
            )}
          </div>
        )}
      </div>
    </div>
  );
}
