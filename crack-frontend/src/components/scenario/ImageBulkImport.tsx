import { useState } from 'react';
import { imageApi, type ImageBulkResult } from '../../api/images';
import { errorMessage } from '../../api/chat';

const CHARACTER_PLACEHOLDER = '{c}';
const ACTION_PLACEHOLDER = '{a}';

const URL_EXAMPLE = `https://example.com/${CHARACTER_PLACEHOLDER}_${ACTION_PLACEHOLDER}.png`;
const CHARACTERS_EXAMPLE = 'F01: 완안연\nF02: 신소향';
const ACTIONS_EXAMPLE = '1: 기본/대화\n2: 미소\n3~4: 놀람';

interface Props {
  scenarioName: string;
  /** 적용에 성공하면 부른다 (원문을 다시 받는다) */
  onApplied: () => void;
}

interface FieldProps {
  label: string;
  hint: string;
  value: string;
  onChange: (value: string) => void;
  placeholder: string;
  rows?: number;
  invalid?: string | null;
}

/** 칸 하나. 여러 줄이면 textarea, 한 줄이면 input */
function Field({ label, hint, value, onChange, placeholder, rows, invalid }: FieldProps) {
  const shared =
    'w-full rounded-xl border bg-bg-primary px-3 py-2.5 font-mono text-[12px] leading-[1.6] text-text-primary ' +
    'placeholder-text-muted focus:outline-none ' +
    (invalid ? 'border-danger/60' : 'border-border focus:border-accent/60');
  return (
    <div>
      <label className="block mb-1 text-[12px] font-medium text-text-primary">{label}</label>
      <p className="mb-1.5 text-[11px] text-text-muted leading-[1.6]">{hint}</p>
      {rows ? (
        <textarea
          value={value}
          onChange={(e) => onChange(e.target.value)}
          placeholder={placeholder}
          rows={rows}
          spellCheck={false}
          className={`${shared} resize-y`}
        />
      ) : (
        <input
          value={value}
          onChange={(e) => onChange(e.target.value)}
          placeholder={placeholder}
          spellCheck={false}
          className={shared}
        />
      )}
      {invalid && <p className="mt-1 text-[11px] text-danger">{invalid}</p>}
    </div>
  );
}

/**
 * 이미지 카탈로그 일괄 등록 (DESIGN.md §8.6, T41·T42).
 *
 * 주소가 `{c}`(캐릭터 코드)·`{a}`(행동·상황 코드) 조합으로 규칙적일 때, **세 칸**만 채우면 조합을 모두 펼친다.
 * **미리보기를 먼저 보여 준다.** 오타 하나가 수십 줄을 오염시키기 때문이다.
 * 적용은 `images.md` 끝에 **덧붙이기만** 하며 이미 있는 태그는 건너뛴다.
 */
export default function ImageBulkImport({ scenarioName, onApplied }: Props) {
  const [open, setOpen] = useState(false);
  const [urlTemplate, setUrlTemplate] = useState('');
  const [characters, setCharacters] = useState('');
  const [actions, setActions] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [result, setResult] = useState<ImageBulkResult | null>(null);
  const [applied, setApplied] = useState<number | null>(null);

  const trimmedUrl = urlTemplate.trim();
  const urlInvalid = trimmedUrl === '' ? null
    : !trimmedUrl.includes(CHARACTER_PLACEHOLDER) ? `${CHARACTER_PLACEHOLDER} 가 없습니다`
    : !trimmedUrl.includes(ACTION_PLACEHOLDER) ? `${ACTION_PLACEHOLDER} 가 없습니다`
    : null;
  const ready = trimmedUrl !== '' && !urlInvalid && characters.trim() !== '' && actions.trim() !== '';

  const run = (apply: boolean) => {
    if (!ready || busy) return;
    setBusy(true);
    setError(null);
    imageApi.bulk(scenarioName, { urlTemplate: trimmedUrl, characters, actions, apply })
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

  // 칸을 고치면 미리보기 결과는 낡은 것이 된다
  const edit = (setter: (value: string) => void) => (value: string) => {
    setter(value);
    setResult(null);
    setApplied(null);
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
      <div className="flex items-center justify-between mb-3">
        <h4 className="text-[13px] font-semibold text-text-primary">주소 규칙으로 일괄 등록</h4>
        <button onClick={() => setOpen(false)} className="text-[12px] text-text-muted hover:text-text-secondary">닫기</button>
      </div>

      <div className="space-y-3">
        <Field
          label="① 주소 형식"
          hint={`${CHARACTER_PLACEHOLDER} 자리에 캐릭터 코드, ${ACTION_PLACEHOLDER} 자리에 행동·상황 코드가 들어갑니다.`}
          value={urlTemplate}
          onChange={edit(setUrlTemplate)}
          placeholder={URL_EXAMPLE}
          invalid={urlInvalid}
        />
        <Field
          label="② 캐릭터 목록"
          hint="한 줄에 `코드: 이름` 하나. 이름은 인물 문서 파일명과 같게 씁니다."
          value={characters}
          onChange={edit(setCharacters)}
          placeholder={CHARACTERS_EXAMPLE}
          rows={5}
        />
        <Field
          label="③ 행동·상황 목록"
          hint="한 줄에 `코드: 이름` 하나. 범위(3~4)는 펼쳐지고, 이름의 `/` 앞이 변형 이름이 됩니다(기본/대화 → 기본). 이름 전체는 AI가 고를 때 보는 설명이 됩니다."
          value={actions}
          onChange={edit(setActions)}
          placeholder={ACTIONS_EXAMPLE}
          rows={7}
        />
      </div>

      <div className="flex gap-2 mt-3">
        <button
          onClick={() => run(false)}
          disabled={busy || !ready}
          className="px-3 py-1.5 rounded-xl text-[13px] bg-surface hover:bg-surface-hover disabled:opacity-40 text-text-secondary transition-all"
        >
          {busy ? '읽는 중…' : '미리보기'}
        </button>
        <button
          onClick={() => run(true)}
          disabled={busy || !result || result.entries.length === 0}
          className="px-3 py-1.5 rounded-xl text-[13px] bg-accent hover:bg-accent-hover disabled:opacity-40 text-white font-medium transition-all"
        >
          {result && result.entries.length > 0 ? `${result.entries.length}개 추가` : '추가'}
        </button>
      </div>

      {error && <p className="mt-2 text-[12px] text-danger">{error}</p>}
      {applied !== null && (
        <p className="mt-2 text-[12px] text-accent">{applied}개를 images.md에 더했습니다.</p>
      )}

      {result && (
        <div className="mt-3 text-[12px]">
          {result.warnings.length > 0 && (
            <ul className="mb-2 space-y-0.5">
              {result.warnings.map((w, i) => <li key={i} className="text-danger">· {w}</li>)}
            </ul>
          )}
          <p className="text-text-muted">
            캐릭터 {result.characters.length}명 × 행동·상황 {result.actions.length}개
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
