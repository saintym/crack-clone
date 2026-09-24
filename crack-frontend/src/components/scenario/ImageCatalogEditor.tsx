import { useState } from 'react';
import {
  DEFAULT_VARIANT, characterTag, isSafeImageUrl, isValidImageTag, parseImageCatalog,
  removeImageEntry, upsertImageEntry, type ImageEntry,
} from '../../api/images';

/** 썸네일. 주소가 없거나 불러오지 못하면 자리만 남긴다 */
function Thumbnail({ entry }: { entry?: ImageEntry }) {
  const [failedUrl, setFailedUrl] = useState<string | null>(null);
  const box = 'w-14 h-14 shrink-0 rounded-xl border flex items-center justify-center text-[10px] text-center leading-tight';
  if (!entry) return <div className={`${box} bg-bg-secondary border-dashed border-border/60 text-text-muted`}>없음</div>;
  if (failedUrl === entry.url) return <div className={`${box} bg-bg-secondary border-danger/40 text-danger`}>불러오지<br />못함</div>;
  return (
    <img
      src={entry.url}
      alt={entry.description || entry.tag}
      loading="lazy"
      referrerPolicy="no-referrer"
      onError={() => setFailedUrl(entry.url)}
      className={`${box} object-cover bg-bg-secondary border-border/40`}
    />
  );
}

interface RowProps {
  tag: string;
  label: string;
  entry?: ImageEntry;
  busy: boolean;
  onSave: (url: string, description: string) => void;
  onRemove?: () => void;
}

/** 항목 한 줄. 주소를 붙여 넣고 Enter나 저장을 누르면 `images.md`에 반영한다 */
function EntryRow({ tag, label, entry, busy, onSave, onRemove }: RowProps) {
  const [url, setUrl] = useState(entry?.url ?? '');
  const [description, setDescription] = useState(entry?.description ?? '');
  const dirty = url.trim() !== (entry?.url ?? '') || description.trim() !== (entry?.description ?? '');
  const invalid = url.trim() !== '' && !isSafeImageUrl(url.trim());
  const save = () => { if (dirty && !invalid && url.trim()) onSave(url.trim(), description.trim()); };

  return (
    <div className="flex gap-3 items-start bg-surface rounded-2xl p-3 border border-border/40">
      <Thumbnail entry={entry} />
      <div className="min-w-0 flex-1 space-y-1.5">
        <div className="flex items-center justify-between gap-2">
          <span className="text-[13px] text-text-primary font-medium">{label}</span>
          <span className="text-[11px] text-text-muted font-mono truncate" title={tag}>{tag}</span>
        </div>
        <input
          type="url"
          inputMode="url"
          value={url}
          onChange={(e) => setUrl(e.target.value)}
          onKeyDown={(e) => { if (e.key === 'Enter') { e.preventDefault(); save(); } }}
          onBlur={save}
          placeholder="이미지 주소 붙여넣기 (https://…)"
          className={`w-full px-3 py-2 bg-bg-secondary border rounded-xl text-text-primary placeholder-text-muted text-[13px] focus:outline-none transition-all ${
            invalid ? 'border-danger/60' : 'border-border/60 focus:border-accent/60'
          }`}
        />
        <div className="flex gap-2">
          <input
            type="text"
            value={description}
            onChange={(e) => setDescription(e.target.value)}
            onKeyDown={(e) => { if (e.key === 'Enter') { e.preventDefault(); save(); } }}
            onBlur={save}
            placeholder="설명(선택) — AI가 이 표정을 고르는 기준"
            className="flex-1 min-w-0 px-3 py-2 bg-bg-secondary border border-border/60 rounded-xl text-text-secondary placeholder-text-muted text-[12px] focus:outline-none focus:border-accent/60 transition-all"
          />
          {dirty && !invalid && url.trim() && (
            <button
              onClick={save}
              disabled={busy}
              className="px-3 py-2 bg-accent hover:bg-accent-hover disabled:opacity-40 text-white rounded-xl text-[12px] font-medium transition-all"
            >
              저장
            </button>
          )}
          {entry && onRemove && (
            <button
              onClick={onRemove}
              disabled={busy}
              title="이 항목 삭제"
              className="w-9 h-9 shrink-0 flex items-center justify-center rounded-xl hover:bg-surface-hover text-text-muted hover:text-danger transition-all"
            >
              <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                <path d="M18 6L6 18M6 6l12 12" />
              </svg>
            </button>
          )}
        </div>
        {invalid && <p className="text-[11px] text-danger">주소는 http:// 또는 https:// 로 시작해야 합니다</p>}
      </div>
    </div>
  );
}

/** 태그 이름을 받아 새 줄을 만드는 작은 폼 */
function AddForm({ label, placeholder, busy, validate, onAdd }: {
  label: string;
  placeholder: string;
  busy: boolean;
  validate: (name: string) => string | null;
  onAdd: (name: string) => void;
}) {
  const [open, setOpen] = useState(false);
  const [name, setName] = useState('');
  const error = name.trim() ? validate(name.trim()) : null;

  if (!open) {
    return (
      <button
        onClick={() => setOpen(true)}
        className="w-full py-2.5 border border-dashed border-border/60 rounded-xl text-text-muted text-[13px] hover:border-accent/60 hover:text-accent transition-all"
      >
        {label}
      </button>
    );
  }
  const submit = () => {
    const trimmed = name.trim();
    if (!trimmed || validate(trimmed)) return;
    onAdd(trimmed);
    setName('');
    setOpen(false);
  };
  return (
    <div className="space-y-1">
      <div className="flex gap-2">
        <input
          type="text"
          value={name}
          onChange={(e) => setName(e.target.value)}
          onKeyDown={(e) => {
            if (e.key === 'Enter') { e.preventDefault(); submit(); }
            if (e.key === 'Escape') { setOpen(false); setName(''); }
          }}
          placeholder={placeholder}
          autoFocus
          className="flex-1 min-w-0 px-3 py-2 bg-bg-secondary border border-border/60 rounded-xl text-text-primary placeholder-text-muted text-[13px] focus:outline-none focus:border-accent/60"
        />
        <button
          onClick={submit}
          disabled={busy || !name.trim() || error !== null}
          className="px-3 py-2 bg-accent hover:bg-accent-hover disabled:opacity-40 text-white rounded-xl text-[12px] font-medium transition-all"
        >
          추가
        </button>
        <button
          onClick={() => { setOpen(false); setName(''); }}
          className="px-3 py-2 bg-surface hover:bg-surface-hover text-text-muted rounded-xl text-[12px] transition-all"
        >
          취소
        </button>
      </div>
      {error && <p className="text-[11px] text-danger">{error}</p>}
    </div>
  );
}

interface EditorProps {
  /** 시나리오의 인물 이름 (`characters/{이름}.md`) */
  characters: string[];
  /** `images.md` 원문 */
  text: string;
  /** 바뀐 원문을 저장한다. 성공하면 true */
  onSave: (next: string) => Promise<boolean>;
}

/**
 * 인물 목록 기반 이미지 등록 화면 (DESIGN.md §8.5, D31).
 *
 * 인물마다 `{이름}_기본` 줄을 보여 주고, 주소만 붙여 넣으면 `images.md`에 항목이 생긴다.
 * 변형(`{이름}_미소`)과 장면 태그도 같은 방식으로 더한다. 저장 형식은 기존 `images.md` 그대로다.
 * **업로드는 없다(D14).** 이미지는 앱 밖에 올리고 주소만 등록한다.
 */
export default function ImageCatalogEditor({ characters, text, onSave }: EditorProps) {
  const [busyTag, setBusyTag] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  /** 아직 주소를 넣지 않아 파일에는 없는 줄 */
  const [pending, setPending] = useState<string[]>([]);

  const { entries, rejected } = parseImageCatalog(text);
  const byTag = new Map(entries.map((e) => [e.tag, e]));
  const isCharacterTag = (tag: string) => characters.some((name) => tag.startsWith(`${name}_`));

  const apply = (tag: string, next: string) => {
    setBusyTag(tag);
    setError(null);
    onSave(next)
      .then((ok) => {
        if (!ok) setError('저장하지 못했습니다. 잠시 뒤 다시 시도하세요.');
        else setPending((p) => p.filter((t) => t !== tag));
      })
      .finally(() => setBusyTag(null));
  };

  const save = (tag: string, url: string, description: string) =>
    apply(tag, upsertImageEntry(text, tag, url, description));

  const remove = (tag: string) => {
    if (byTag.has(tag)) apply(tag, removeImageEntry(text, tag));
    setPending((p) => p.filter((t) => t !== tag));
  };

  const rowsFor = (name: string): string[] => {
    const prefix = `${name}_`;
    const registered = entries.filter((e) => e.tag.startsWith(prefix)).map((e) => e.tag);
    const all = [characterTag(name), ...registered, ...pending.filter((t) => t.startsWith(prefix))];
    return [...new Set(all)];
  };

  const validateTag = (tag: string): string | null => {
    if (!isValidImageTag(tag)) return '공백과 : { } | 는 쓸 수 없습니다';
    if (byTag.has(tag) || pending.includes(tag)) return '이미 있는 태그입니다';
    return null;
  };

  const sceneTags = [
    ...entries.filter((e) => !isCharacterTag(e.tag)).map((e) => e.tag),
    ...pending.filter((t) => !isCharacterTag(t) && !byTag.has(t)),
  ];

  return (
    <div className="space-y-5">
      {error && <p className="text-[13px] text-danger bg-danger/10 rounded-xl px-3 py-2">{error}</p>}

      {characters.length === 0 && (
        <p className="text-xs text-text-muted">등록된 인물이 없습니다. 캐릭터 탭에서 인물을 먼저 추가하세요.</p>
      )}

      {characters.map((name) => (
        <section key={name} className="space-y-2">
          <h3 className="text-[15px] text-text-primary font-semibold">{name}</h3>
          {rowsFor(name).map((tag) => (
            <EntryRow
              key={`${tag}|${byTag.get(tag)?.url ?? ''}`}
              tag={tag}
              label={tag.slice(name.length + 1) || DEFAULT_VARIANT}
              entry={byTag.get(tag)}
              busy={busyTag === tag}
              onSave={(url, description) => save(tag, url, description)}
              onRemove={tag === characterTag(name) && !byTag.has(tag) ? undefined : () => remove(tag)}
            />
          ))}
          <AddForm
            label="+ 표정·감정 추가"
            placeholder={`변형 이름 (예: 미소) → ${name}_미소`}
            busy={busyTag !== null}
            validate={(variant) => validateTag(characterTag(name, variant))}
            onAdd={(variant) => setPending((p) => [...p, characterTag(name, variant)])}
          />
        </section>
      ))}

      <section className="space-y-2">
        <h3 className="text-[15px] text-text-primary font-semibold">장면·배경</h3>
        <p className="text-[11px] text-text-muted">인물 이미지와 달리 AI가 본문에 직접 넣습니다.</p>
        {sceneTags.map((tag) => (
          <EntryRow
            key={`${tag}|${byTag.get(tag)?.url ?? ''}`}
            tag={tag}
            label={tag}
            entry={byTag.get(tag)}
            busy={busyTag === tag}
            onSave={(url, description) => save(tag, url, description)}
            onRemove={() => remove(tag)}
          />
        ))}
        <AddForm
          label="+ 장면 태그 추가"
          placeholder="태그 이름 (예: 객잔_밤)"
          busy={busyTag !== null}
          validate={validateTag}
          onAdd={(tag) => setPending((p) => [...p, tag])}
        />
      </section>

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
