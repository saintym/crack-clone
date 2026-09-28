import { useState } from 'react';
import { imageApi, type ImageBulkResult } from '../../api/images';
import { errorMessage } from '../../api/chat';

const PLACEHOLDER = `#형식: ![](https://example.com/{c}_{a}.png)

# 캐릭터 코드
*F01: 완안연
*F02: 신소향

# 행동
*1: 기본/대화
*2: 미소
*3~4: 놀람`;

interface Props {
  scenarioName: string;
  /** 적용에 성공하면 부른다 (원문을 다시 받는다) */
  onApplied: () => void;
}

/**
 * 이미지 카탈로그 일괄 등록 (DESIGN.md §8.6, T41).
 *
 * 주소가 `{캐릭터 코드}`·`{행동 코드}` 조합으로 규칙적일 때, 표 두 개와 주소 틀만 넣으면 조합을 모두 펼친다.
 * **미리보기를 먼저 보여 준다.** 오타 하나가 수십 줄을 오염시키기 때문이다.
 * 적용은 `images.md` 끝에 **덧붙이기만** 하며 이미 있는 태그는 건너뛴다.
 */
export default function ImageBulkImport({ scenarioName, onApplied }: Props) {
  const [open, setOpen] = useState(false);
  const [spec, setSpec] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [result, setResult] = useState<ImageBulkResult | null>(null);
  const [applied, setApplied] = useState<number | null>(null);

  const run = (apply: boolean) => {
    if (!spec.trim() || busy) return;
    setBusy(true);
    setError(null);
    imageApi.bulk(scenarioName, spec, apply)
      .then(({ data }) => {
        setResult(data);
        if (apply) {
          setApplied(data.added);
          if (data.added > 0) onApplied();
        } else {
          setApplied(null);
        }
      })
      .catch((err) => setError(errorMessage(err, '일괄 등록에 실패했습니다')))
      .finally(() => setBusy(false));
  };

  if (!open) {
    return (
      <button
        onClick={() => setOpen(true)}
        className="w-full rounded-xl border border-dashed border-border/60 px-3 py-2.5 text-[13px] text-text-secondary hover:bg-surface-hover transition-all"
      >
        주소 규칙으로 일괄 등록
      </button>
    );
  }

  return (
    <div className="rounded-xl border border-border/60 bg-bg-secondary p-3">
      <div className="flex items-center justify-between mb-2">
        <h4 className="text-[13px] font-semibold text-text-primary">주소 규칙으로 일괄 등록</h4>
        <button onClick={() => setOpen(false)} className="text-[12px] text-text-muted hover:text-text-secondary">닫기</button>
      </div>

      <p className="mb-2 text-[12px] text-text-muted leading-[1.6]">
        주소가 <code>{'{c}'}</code>(캐릭터 코드)와 <code>{'{a}'}</code>(행동 코드) 조합이면 표만 넣어 한 번에 등록합니다.
        범위(<code>3~4</code>)는 펼쳐지고, 라벨의 <code>/</code> 앞이 변형 이름이 됩니다(<code>기본/대화</code> → <code>기본</code>).
      </p>

      <textarea
        value={spec}
        onChange={(e) => setSpec(e.target.value)}
        placeholder={PLACEHOLDER}
        rows={10}
        spellCheck={false}
        className="w-full rounded-xl border border-border bg-bg-primary px-3 py-2.5 font-mono text-[12px] leading-[1.6] text-text-primary focus:outline-none focus:border-accent/60 resize-y"
      />

      <div className="flex gap-2 mt-2">
        <button
          onClick={() => run(false)}
          disabled={busy || !spec.trim()}
          className="px-3 py-1.5 rounded-xl text-[13px] bg-surface hover:bg-surface-hover disabled:opacity-40 text-text-secondary transition-all"
        >
          {busy ? '읽는 중…' : '미리보기'}
        </button>
        <button
          onClick={() => run(true)}
          disabled={busy || !result || result.entries.length === 0}
          className="px-3 py-1.5 rounded-xl text-[13px] bg-accent hover:bg-accent-hover disabled:opacity-40 text-white font-medium transition-all"
        >
          {result ? `${result.entries.length}개 추가` : '추가'}
        </button>
      </div>

      {error && <p className="mt-2 text-[12px] text-danger">{error}</p>}

      {applied !== null && (
        <p className="mt-2 text-[12px] text-accent">{applied}개를 `images.md`에 더했습니다.</p>
      )}

      {result && (
        <div className="mt-3 text-[12px]">
          {result.warnings.length > 0 && (
            <ul className="mb-2 space-y-0.5">
              {result.warnings.map((w, i) => <li key={i} className="text-danger">· {w}</li>)}
            </ul>
          )}
          <p className="text-text-muted">
            인물 {result.characters.length}명 × 행동 {result.actions.length}개
            {result.duplicates > 0 && ` · 이미 있어 건너뜀 ${result.duplicates}개`}
          </p>
          {result.entries.length > 0 && (
            <div className="mt-2 max-h-52 overflow-y-auto rounded-xl border border-border/40 bg-bg-primary p-2 font-mono text-[11px] leading-[1.7]">
              {result.entries.map((e) => (
                <div key={e.tag} className="truncate text-text-secondary">
                  <span className="text-text-primary">{e.tag}</span>: {e.url}
                  {e.description && <span className="text-text-muted"> | {e.description}</span>}
                </div>
              ))}
            </div>
          )}
        </div>
      )}
    </div>
  );
}
