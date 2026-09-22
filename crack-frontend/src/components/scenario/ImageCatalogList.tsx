import { useState } from 'react';
import { parseImageCatalog, type ImageEntry } from '../../api/images';

/** 썸네일. 불러오지 못하면 자리에 표시만 남긴다 */
function Thumbnail({ entry }: { entry: ImageEntry }) {
  const [failedUrl, setFailedUrl] = useState<string | null>(null);
  if (failedUrl === entry.url) {
    return (
      <div className="w-16 h-16 shrink-0 rounded-xl bg-bg-secondary border border-danger/40 flex items-center justify-center text-[10px] text-danger text-center leading-tight">
        불러오지<br />못함
      </div>
    );
  }
  return (
    <img
      src={entry.url}
      alt={entry.description || entry.tag}
      loading="lazy"
      referrerPolicy="no-referrer"
      onError={() => setFailedUrl(entry.url)}
      className="w-16 h-16 shrink-0 rounded-xl object-cover bg-bg-secondary border border-border/40"
    />
  );
}

/**
 * `images.md` 미리보기 (DESIGN.md §8.5). 인식한 항목을 썸네일과 함께 보여 주고, 형식이 맞지 않아 버린 줄을 알려 준다.
 * 내용은 모두 텍스트와 `<img src>`로만 넣는다(URL은 http/https만 통과).
 */
export default function ImageCatalogList({ text }: { text: string }) {
  const { entries, rejected } = parseImageCatalog(text);

  return (
    <div className="space-y-2.5">
      <p className="text-xs text-text-muted">인식한 이미지 {entries.length}개</p>
      {entries.map((entry) => (
        <div key={entry.tag} className="flex gap-3 items-center bg-surface rounded-2xl p-3 border border-border/40">
          <Thumbnail entry={entry} />
          <div className="min-w-0 flex-1">
            <p className="text-[14px] text-text-primary font-mono break-all">{`{{img:${entry.tag}}}`}</p>
            {entry.description && <p className="text-[13px] text-text-secondary mt-0.5">{entry.description}</p>}
            <p className="text-[11px] text-text-muted truncate mt-0.5" title={entry.url}>{entry.url}</p>
          </div>
        </div>
      ))}
      {rejected.length > 0 && (
        <div className="rounded-2xl p-3 border border-danger/40 text-xs text-text-secondary space-y-1">
          <p className="text-danger">형식이나 주소가 맞지 않아 무시되는 줄 {rejected.length}개 (주소는 http/https만)</p>
          {rejected.map((line, i) => (
            <p key={i} className="font-mono break-all text-text-muted">{line}</p>
          ))}
        </div>
      )}
    </div>
  );
}
