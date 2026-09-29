import { useRef, useState } from 'react';
import { ChatApiError, errorMessage } from '../../api/chat';
import {
  scenarioCreateApi,
  type CreateCharacter,
  type CreateDone,
  type CreatePreview,
  type CreateRoundResponse,
  type CreateStep,
} from '../../api/scenarioCreate';

type Phase = 'seed' | 'asking' | 'preview' | 'name' | 'running' | 'done';

interface Props {
  onClose: () => void;
  /** 생성이 끝났을 때. 목록을 다시 받고 상세로 보낸다 */
  onCreated: (done: CreateDone) => void;
}

/** 서버 기본값(`crack.create.max-rounds`)과 맞춘 표시용 값. 끝은 `done: true`가 정한다 */
const MAX_ROUNDS = 3;

const sheet = 'w-full max-w-lg bg-bg-secondary rounded-t-3xl px-5 sm:px-6 pt-5 pb-8 safe-bottom border-t border-border/50 max-h-[90vh] overflow-y-auto';
const field = 'w-full px-4 py-3 bg-surface border border-border rounded-2xl text-text-primary placeholder-text-muted focus:outline-none focus:border-accent/60 transition-all text-[15px]';
const primary = 'flex-1 min-w-0 py-3.5 bg-accent hover:bg-accent-hover disabled:opacity-30 text-white rounded-2xl font-semibold transition-all text-[15px]';
const secondary = 'flex-1 min-w-0 py-3.5 bg-surface hover:bg-surface-hover text-text-secondary rounded-2xl font-medium transition-all text-[15px]';

/** 초를 "약 4분"처럼 */
function minutes(seconds: number): string {
  if (seconds < 90) return `약 ${Math.max(1, Math.round(seconds / 10) * 10)}초`;
  return `약 ${Math.round(seconds / 60)}분`;
}

/** 미리보기의 한 문단 (없으면 줄을 지운다) */
function PreviewBlock({ label, text }: { label: string; text: string }) {
  if (!text.trim()) return null;
  return (
    <div className="min-w-0">
      <div className="text-[12px] text-text-muted mb-0.5">{label}</div>
      <p className="text-[13px] text-text-secondary leading-relaxed whitespace-pre-wrap break-words">{text}</p>
    </div>
  );
}

/**
 * 질문에 답해 시나리오 만들기 (DESIGN.md §11.6, T48 API).
 *
 * 씨앗 → 질문 라운드 → 미리보기(인물 편집) → 이름 확인 → 생성(SSE).
 * 모달이 아니라 하단 시트다(D7).
 */
export default function ScenarioCreateSheet({ onClose, onCreated }: Props) {
  const [phase, setPhase] = useState<Phase>('seed');
  const [seed, setSeed] = useState('');
  const [round, setRound] = useState<CreateRoundResponse | null>(null);
  const [answers, setAnswers] = useState<Record<string, string>>({});
  const [preview, setPreview] = useState<CreatePreview | null>(null);
  const [characters, setCharacters] = useState<CreateCharacter[]>([]);
  const [draft, setDraft] = useState<CreateCharacter>({ name: '', role: '', note: '' });
  const [name, setName] = useState('');
  const [title, setTitle] = useState('');
  const [step, setStep] = useState<CreateStep | null>(null);
  const [done, setDone] = useState<CreateDone | null>(null);
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  const abort = useRef<AbortController | null>(null);

  /** 만료된 작업(404)은 되돌릴 수 없다. 씨앗은 남겨 두고 처음으로 보낸다 */
  const handleError = (err: unknown, fallback: string) => {
    if (err instanceof ChatApiError && err.status === 404) {
      setRound(null);
      setPhase('seed');
      setError('작업이 만료됐습니다(30분). 씨앗부터 다시 시작해 주세요');
      return;
    }
    setError(errorMessage(err, fallback));
  };

  /** start와 answer가 같은 응답을 준다. done이면 미리보기로, 아니면 다음 질문으로 */
  const applyRound = (data: CreateRoundResponse) => {
    setRound(data);
    setName(data.suggestedName);
    setTitle(data.title);
    if (data.done && data.preview) {
      setPreview(data.preview);
      setCharacters(data.preview.characters);
      setPhase('preview');
    } else {
      setAnswers({});
      setPhase('asking');
    }
  };

  const start = async () => {
    const text = seed.trim();
    if (!text || busy) return;
    setBusy(true);
    setError('');
    try {
      const { data } = await scenarioCreateApi.start(text);
      applyRound(data);
    } catch (err) {
      handleError(err, '질문을 받지 못했습니다');
    } finally {
      setBusy(false);
    }
  };

  /**
   * 이번 라운드 답을 보낸다. `blank`면 전부 비우고 넘긴다
   * ("모르겠어요, 알아서 정해주세요" — 서버가 "네가 정하라"로 받는다)
   */
  const submitAnswers = async (blank: boolean) => {
    if (!round || busy) return;
    setBusy(true);
    setError('');
    try {
      const { data } = await scenarioCreateApi.answer(round.jobId, blank ? {} : answers);
      applyRound(data);
    } catch (err) {
      handleError(err, '다음 질문을 받지 못했습니다');
    } finally {
      setBusy(false);
    }
  };

  const addCharacter = () => {
    const trimmed = draft.name.trim();
    if (!trimmed) return;
    setCharacters((prev) => [
      ...prev,
      { name: trimmed, role: draft.role.trim(), note: draft.note.trim() },
    ]);
    setDraft({ name: '', role: '', note: '' });
  };

  const create = async () => {
    if (!round || !name.trim() || busy) return;
    setBusy(true);
    setError('');
    setStep(null);
    setPhase('running');
    const controller = new AbortController();
    abort.current = controller;
    try {
      const result = await scenarioCreateApi.confirm(
        round.jobId,
        { name: name.trim(), title: title.trim() || undefined, characters },
        setStep,
        controller.signal,
      );
      if (result.type === 'done') {
        setDone(result.done);
        setPhase('done');
      } else if (result.type === 'error') {
        setError(result.message);
        setPhase('name');
      } else {
        setError('연결이 끊겼습니다. 서버는 계속 만들고 있을 수 있으니 목록을 확인해 주세요');
        setPhase('name');
      }
    } catch (err) {
      // 이름 중복은 서버가 400을 준다. 문구를 그대로 보여 주고 이름 확인으로 되돌린다
      setPhase('name');
      handleError(err, '시나리오를 만들지 못했습니다');
    } finally {
      abort.current = null;
      setBusy(false);
    }
  };

  const close = () => {
    abort.current?.abort();
    onClose();
  };

  const rounds = Math.max(round?.round ?? 1, MAX_ROUNDS);

  return (
    <div
      className="fixed inset-0 z-50 flex items-end justify-center bg-black/50 backdrop-blur-sm"
      onClick={phase === 'running' ? undefined : close}
    >
      <div className={sheet} onClick={(e) => e.stopPropagation()}>
        <div className="w-10 h-1 bg-border-light/50 rounded-full mx-auto mb-5" />
        <h2 className="text-lg font-semibold text-text-primary mb-1">질문으로 만들기</h2>
        <p className="text-[12px] text-text-muted mb-5 leading-relaxed">
          한 줄만 적으면 AI가 되물어 가며 시나리오 한 벌을 만듭니다. 답하기 어려운 것은 비워 두세요.
        </p>

        {error && (
          <div className="mb-4 px-4 py-3 rounded-2xl bg-danger/10 border border-danger/30 text-danger text-[13px] leading-relaxed whitespace-pre-wrap break-words">
            {error}
          </div>
        )}

        {phase === 'seed' && (
          <>
            <textarea
              value={seed}
              onChange={(e) => setSeed(e.target.value)}
              rows={4}
              placeholder={'무협. 주인공은 몰락한 가문의 검객.\n분위기는 어둡고 건조하게.'}
              autoFocus
              className={`${field} resize-none leading-relaxed`}
            />
            <p className="text-[12px] text-text-muted mt-3 leading-relaxed">
              장르·주인공·분위기 정도면 충분합니다. 문파 이름이나 지명 같은 설정은 AI가 채웁니다.
              <br />
              질문은 최대 {MAX_ROUNDS}라운드입니다.
            </p>
            <div className="flex gap-3 mt-6">
              <button onClick={close} className={secondary}>취소</button>
              <button onClick={start} disabled={!seed.trim() || busy} className={primary}>
                {busy ? '질문 만드는 중…' : '시작'}
              </button>
            </div>
          </>
        )}

        {phase === 'asking' && round && (
          <>
            <div className="flex items-center gap-3 mb-4">
              <div className="flex-1 min-w-0 h-1.5 rounded-full bg-surface overflow-hidden">
                <div
                  className="h-full bg-accent transition-all duration-500"
                  style={{ width: `${Math.round((round.round / rounds) * 100)}%` }}
                />
              </div>
              <span className="shrink-0 text-[12px] text-text-muted">
                {round.round} / {rounds}
              </span>
            </div>

            <div className="space-y-4">
              {round.questions.map((q) => (
                <label key={q.id} className="block min-w-0">
                  <span className="block text-[14px] text-text-primary mb-1.5 leading-relaxed break-words">
                    {q.text}
                  </span>
                  <input
                    type="text"
                    value={answers[q.id] ?? ''}
                    onChange={(e) => setAnswers((prev) => ({ ...prev, [q.id]: e.target.value }))}
                    placeholder={q.placeholder}
                    disabled={busy}
                    className={field}
                  />
                </label>
              ))}
              {round.questions.length === 0 && (
                <p className="text-[13px] text-text-secondary leading-relaxed">
                  이번 라운드에는 물어볼 것이 없다고 합니다. 다음으로 넘어가세요.
                </p>
              )}
            </div>

            <button
              onClick={() => submitAnswers(true)}
              disabled={busy}
              className="w-full mt-4 py-3 rounded-2xl border border-border/60 text-text-muted hover:text-text-secondary hover:bg-surface-hover disabled:opacity-40 transition-all text-[13px]"
            >
              모르겠어요, 알아서 정해주세요
            </button>
            <p className="text-[12px] text-text-muted mt-2 leading-relaxed">
              일부만 비워 두고 넘겨도 됩니다. 비운 것은 AI가 정합니다.
            </p>

            <div className="flex gap-3 mt-6">
              <button onClick={close} disabled={busy} className={secondary}>취소</button>
              <button onClick={() => submitAnswers(false)} disabled={busy} className={primary}>
                {busy ? '생각하는 중…' : '다음'}
              </button>
            </div>
          </>
        )}

        {phase === 'preview' && preview && (
          <>
            <div className="rounded-2xl bg-surface border border-border/40 px-4 py-3 mb-4 space-y-2.5 min-w-0">
              <div className="text-text-primary font-medium break-words">{preview.title || '(제목 없음)'}</div>
              <PreviewBlock label="세계관" text={preview.world} />
              <PreviewBlock label="주인공" text={preview.protagonist} />
              <PreviewBlock label="시작 장면" text={preview.opening} />
            </div>

            <div className="text-[13px] text-text-primary mb-2">
              인물 {characters.length}명
              <span className="text-[12px] text-text-muted ml-2">지우면 만들지 않습니다</span>
            </div>
            <div className="space-y-2">
              {characters.map((c, i) => (
                <div
                  key={`${c.name}-${i}`}
                  className="flex items-start gap-2 rounded-2xl bg-surface border border-border/40 px-3.5 py-2.5 min-w-0"
                >
                  <div className="flex-1 min-w-0">
                    <div className="text-[13px] text-text-primary break-words">{c.name}</div>
                    {(c.role || c.note) && (
                      <div className="text-[12px] text-text-muted leading-relaxed break-words">
                        {[c.role, c.note].filter(Boolean).join(' · ')}
                      </div>
                    )}
                  </div>
                  <button
                    onClick={() => setCharacters((prev) => prev.filter((_, idx) => idx !== i))}
                    title="이 인물 지우기"
                    className="shrink-0 w-7 h-7 flex items-center justify-center rounded-full text-text-muted hover:text-danger hover:bg-surface-hover transition-all"
                  >
                    <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round">
                      <path d="M18 6L6 18M6 6l12 12" />
                    </svg>
                  </button>
                </div>
              ))}
              {characters.length === 0 && (
                <p className="text-[12px] text-text-muted">
                  인물이 없으면 주인공만 만듭니다.
                </p>
              )}
            </div>

            <div className="mt-4 space-y-2">
              <input
                type="text"
                value={draft.name}
                onChange={(e) => setDraft((d) => ({ ...d, name: e.target.value }))}
                onKeyDown={(e) => { if (e.key === 'Enter') { e.preventDefault(); addCharacter(); } }}
                placeholder="더할 인물 이름"
                className={field}
              />
              <input
                type="text"
                value={draft.role}
                onChange={(e) => setDraft((d) => ({ ...d, role: e.target.value }))}
                placeholder="역할 (예: 화산파 일대제자)"
                className={field}
              />
              <input
                type="text"
                value={draft.note}
                onChange={(e) => setDraft((d) => ({ ...d, note: e.target.value }))}
                onKeyDown={(e) => { if (e.key === 'Enter') { e.preventDefault(); addCharacter(); } }}
                placeholder="주인공과의 관계 (예: 오랜 악연)"
                className={field}
              />
              <button
                onClick={addCharacter}
                disabled={!draft.name.trim()}
                className="w-full py-3 rounded-2xl border border-border/60 text-text-secondary hover:bg-surface-hover disabled:opacity-30 transition-all text-[13px]"
              >
                인물 더하기
              </button>
            </div>

            <div className="flex gap-3 mt-6">
              <button onClick={close} className={secondary}>취소</button>
              <button onClick={() => { setError(''); setPhase('name'); }} className={primary}>다음</button>
            </div>
          </>
        )}

        {phase === 'name' && round && (
          <>
            <p className="text-[13px] text-text-secondary mb-3 leading-relaxed">
              시나리오 ID는 폴더 이름이 됩니다. 이미 있는 이름이면 만들지 않습니다.
            </p>
            <div className="space-y-3">
              <input
                type="text"
                value={name}
                onChange={(e) => setName(e.target.value)}
                placeholder="시나리오 ID (폴더 이름)"
                autoFocus
                className={field}
              />
              <input
                type="text"
                value={title}
                onChange={(e) => setTitle(e.target.value)}
                placeholder="시나리오 제목"
                className={field}
              />
            </div>
            <div className="mt-4 rounded-2xl bg-surface border border-border/40 px-4 py-3 text-[12px] text-text-muted leading-relaxed">
              인물 {characters.length}명 · 예상 LLM {round.estimatedLlmCalls}회 · {minutes(round.estimatedSeconds)}
            </div>
            <div className="flex gap-3 mt-6">
              <button onClick={() => { setError(''); setPhase('preview'); }} className={secondary}>뒤로</button>
              <button onClick={create} disabled={!name.trim() || busy} className={primary}>만들기</button>
            </div>
          </>
        )}

        {phase === 'running' && (
          <div className="py-4">
            <div className="h-2 rounded-full bg-surface overflow-hidden">
              <div
                className="h-full bg-accent transition-all duration-500"
                style={{ width: `${step?.percent ?? 3}%` }}
              />
            </div>
            <div className="mt-4 text-[14px] text-text-primary break-words">
              {step ? `${step.index}/${step.total} ${step.label}` : '시작하는 중…'}
            </div>
            {step?.detail && <div className="mt-1 text-[13px] text-text-muted break-words">{step.detail}</div>}
            <p className="mt-5 text-[12px] text-text-muted leading-relaxed">
              인물이 많으면 몇 분 걸립니다. <span className="text-text-secondary">이 창을 닫아도 서버는 계속 만듭니다.</span>{' '}
              다만 진행 상황은 다시 볼 수 없으니, 나중에 목록을 새로고침해 확인하세요.
            </p>
            <div className="flex gap-3 mt-6">
              <button onClick={close} className={secondary}>닫고 나가기</button>
            </div>
          </div>
        )}

        {phase === 'done' && done && (
          <div className="py-2">
            <div className="rounded-2xl bg-surface border border-border/40 px-4 py-3 text-[13px] text-text-secondary space-y-1 min-w-0">
              <div className="text-text-primary font-medium break-words">{done.title}</div>
              <div>인물 {done.characterCount}명 · 파일 {done.files.length}개</div>
              <div className="text-[12px] text-text-muted">LLM {done.llmCalls}회 · {done.elapsedSeconds}초</div>
            </div>
            <p className="mt-4 text-[13px] text-text-secondary leading-relaxed">
              AI가 쓴 초안입니다. <span className="text-text-primary">문서를 확인하고 다듬으세요.</span>
              <br />
              인물 이미지는 주소가 없어 등록할 태그만 주석으로 남겼습니다.
            </p>
            <div className="flex gap-3 mt-6">
              <button onClick={close} className={secondary}>닫기</button>
              <button onClick={() => onCreated(done)} className={primary}>시나리오 열기</button>
            </div>
          </div>
        )}
      </div>
    </div>
  );
}
