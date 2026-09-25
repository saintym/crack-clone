import { useRef, useState } from 'react';
import { errorMessage } from '../../api/chat';
import {
  scenarioImportApi,
  type ImportAnalysis,
  type ImportDone,
  type ImportStep,
} from '../../api/scenarioImport';

type Phase = 'url' | 'review' | 'running' | 'done';

interface Props {
  onClose: () => void;
  /** 생성이 끝났을 때. 목록을 다시 받고 상세로 보낸다 */
  onCreated: (done: ImportDone) => void;
}

const sheet = 'w-full max-w-lg bg-bg-secondary rounded-t-3xl px-6 pt-5 pb-8 safe-bottom border-t border-border/50 max-h-[90vh] overflow-y-auto';
const field = 'w-full px-4 py-3 bg-surface border border-border rounded-2xl text-text-primary placeholder-text-muted focus:outline-none focus:border-accent/60 transition-all text-[15px]';
const primary = 'flex-1 py-3.5 bg-accent hover:bg-accent-hover disabled:opacity-30 text-white rounded-2xl font-semibold transition-all text-[15px]';
const secondary = 'flex-1 py-3.5 bg-surface hover:bg-surface-hover text-text-secondary rounded-2xl font-medium transition-all text-[15px]';

/** 초를 "약 4분"처럼 */
function minutes(seconds: number): string {
  if (seconds < 90) return `약 ${Math.max(1, Math.round(seconds / 10) * 10)}초`;
  return `약 ${Math.round(seconds / 60)}분`;
}

/**
 * URL로 시나리오 가져오기 (DESIGN.md §11.5).
 *
 * 1. 주소 입력 → 분석 2. 분석 결과 확인과 질문 답변 3. 생성 진행(SSE) 4. 완료
 */
export default function ScenarioImportSheet({ onClose, onCreated }: Props) {
  const [phase, setPhase] = useState<Phase>('url');
  const [url, setUrl] = useState('');
  const [analysis, setAnalysis] = useState<ImportAnalysis | null>(null);
  const [name, setName] = useState('');
  const [title, setTitle] = useState('');
  const [answers, setAnswers] = useState<Record<string, string>>({});
  const [step, setStep] = useState<ImportStep | null>(null);
  const [done, setDone] = useState<ImportDone | null>(null);
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  const abort = useRef<AbortController | null>(null);

  const analyze = async () => {
    const target = url.trim();
    if (!target || busy) return;
    setBusy(true);
    setError('');
    try {
      const { data } = await scenarioImportApi.analyze(target);
      setAnalysis(data);
      setName(data.suggestedName);
      setTitle(data.title);
      setAnswers({});
      setPhase('review');
    } catch (err) {
      setError(errorMessage(err, '페이지를 분석하지 못했습니다'));
    } finally {
      setBusy(false);
    }
  };

  const create = async () => {
    if (!analysis || !name.trim() || busy) return;
    setBusy(true);
    setError('');
    setStep(null);
    setPhase('running');
    const controller = new AbortController();
    abort.current = controller;
    try {
      const result = await scenarioImportApi.confirm(
        analysis.jobId,
        { name: name.trim(), title: title.trim() || undefined, answers },
        setStep,
        controller.signal,
      );
      if (result.type === 'done') {
        setDone(result.done);
        setPhase('done');
      } else if (result.type === 'error') {
        setError(result.message);
        setPhase('review');
      } else {
        setError('연결이 끊겼습니다. 서버는 계속 만들고 있을 수 있으니 목록을 확인해 주세요');
        setPhase('review');
      }
    } catch (err) {
      setError(errorMessage(err, '시나리오를 만들지 못했습니다'));
      setPhase('review');
    } finally {
      abort.current = null;
      setBusy(false);
    }
  };

  const close = () => {
    abort.current?.abort();
    onClose();
  };

  return (
    <div className="fixed inset-0 z-50 flex items-end justify-center bg-black/50 backdrop-blur-sm" onClick={phase === 'running' ? undefined : close}>
      <div className={sheet} onClick={(e) => e.stopPropagation()}>
        <div className="w-10 h-1 bg-border-light/50 rounded-full mx-auto mb-5" />
        <h2 className="text-lg font-semibold text-text-primary mb-1">URL로 가져오기</h2>
        <p className="text-[12px] text-text-muted mb-5 leading-relaxed">
          설정이 정리된 웹페이지를 AI가 읽어 시나리오 한 벌을 만듭니다.
          <br />
          남의 페이지를 가져올 때는 개인 이용 범위에서 쓰세요.
        </p>

        {error && (
          <div className="mb-4 px-4 py-3 rounded-2xl bg-danger/10 border border-danger/30 text-danger text-[13px] leading-relaxed whitespace-pre-wrap">
            {error}
          </div>
        )}

        {phase === 'url' && (
          <>
            <input
              type="url"
              inputMode="url"
              value={url}
              onChange={(e) => setUrl(e.target.value)}
              onKeyDown={(e) => { if (e.key === 'Enter') { e.preventDefault(); analyze(); } }}
              placeholder="https://example.com/setting.html"
              autoFocus
              className={field}
            />
            <p className="text-[12px] text-text-muted mt-3">
              페이지를 읽는 데 20~40초 걸립니다. 인물이 많으면 생성까지 3~6분입니다.
            </p>
            <div className="flex gap-3 mt-6">
              <button onClick={close} className={secondary}>취소</button>
              <button onClick={analyze} disabled={!url.trim() || busy} className={primary}>
                {busy ? '분석 중…' : '분석'}
              </button>
            </div>
          </>
        )}

        {phase === 'review' && analysis && (
          <>
            <div className="rounded-2xl bg-surface border border-border/40 px-4 py-3 mb-4 text-[13px] text-text-secondary space-y-1">
              <div className="text-text-primary font-medium">{analysis.title || '(제목 없음)'}</div>
              <div>인물 {analysis.characterCount}명 · 이미지 {analysis.imageCount}개</div>
              {analysis.characters.length > 0 && (
                <div className="text-[12px] text-text-muted leading-relaxed">
                  {analysis.characters.slice(0, 12).map((c) => c.name).join(', ')}
                  {analysis.characters.length > 12 ? ` 외 ${analysis.characters.length - 12}명` : ''}
                </div>
              )}
              {(analysis.truncated.droppedBodyChars > 0 || analysis.truncated.droppedBlocks > 0) && (
                <div className="text-[12px] text-text-muted">
                  분량이 커서 일부를 생략했습니다
                  {analysis.truncated.droppedBodyChars > 0 ? ` (본문 ${analysis.truncated.droppedBodyChars.toLocaleString()}자` : ' ('}
                  {analysis.truncated.droppedBlocks > 0 ? `${analysis.truncated.droppedBodyChars > 0 ? ', ' : ''}데이터 ${analysis.truncated.droppedBlocks}개` : ''}
                  )
                </div>
              )}
              <div className="text-[12px] text-text-muted">
                예상: LLM {analysis.estimatedLlmCalls}회 · {minutes(analysis.estimatedSeconds)}
              </div>
            </div>

            <div className="space-y-3">
              <input
                type="text"
                value={name}
                onChange={(e) => setName(e.target.value)}
                placeholder="시나리오 ID (폴더 이름)"
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

            {analysis.questions.length > 0 && (
              <div className="mt-5 space-y-3">
                <p className="text-[13px] text-text-secondary">
                  페이지에 없는 것들입니다. 답한 내용이 주인공과 첫 장면에 들어갑니다.
                </p>
                {analysis.questions.map((q) => (
                  <label key={q.id} className="block">
                    <span className="block text-[13px] text-text-primary mb-1.5">{q.text}</span>
                    <input
                      type="text"
                      value={answers[q.id] ?? ''}
                      onChange={(e) => setAnswers((prev) => ({ ...prev, [q.id]: e.target.value }))}
                      placeholder={q.placeholder}
                      className={field}
                    />
                  </label>
                ))}
              </div>
            )}

            <div className="flex gap-3 mt-6">
              <button onClick={() => { setPhase('url'); setError(''); }} className={secondary}>뒤로</button>
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
            <div className="mt-4 text-[14px] text-text-primary">
              {step ? `${step.index}/${step.total} ${step.label}` : '시작하는 중…'}
            </div>
            {step?.detail && <div className="mt-1 text-[13px] text-text-muted">{step.detail}</div>}
            <p className="mt-5 text-[12px] text-text-muted leading-relaxed">
              인물이 많으면 몇 분 걸립니다. 이 창을 닫아도 서버는 계속 만들지만, 진행 상황은 다시 볼 수 없습니다.
            </p>
          </div>
        )}

        {phase === 'done' && done && (
          <div className="py-2">
            <div className="rounded-2xl bg-surface border border-border/40 px-4 py-3 text-[13px] text-text-secondary space-y-1">
              <div className="text-text-primary font-medium">{done.title}</div>
              <div>인물 {done.characterCount}명 · 파일 {done.files.length}개</div>
              <div className="text-[12px] text-text-muted">LLM {done.llmCalls}회 · {done.elapsedSeconds}초</div>
            </div>
            <p className="mt-4 text-[13px] text-text-secondary leading-relaxed">
              AI가 옮긴 초안입니다. <span className="text-text-primary">문서를 확인하고 다듬으세요.</span>
              <br />
              인물 이미지는 이미지 탭에서 확인할 수 있습니다.
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
