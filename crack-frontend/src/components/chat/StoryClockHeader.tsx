import type { ReadingMode } from '../../hooks/useReadingMode';
import { storyClockLine, type StoryClockHeaderData } from './storyClock';

interface StoryClockHeaderProps {
  data: StoryClockHeaderData;
  /** 'novel'이면 턴 구분선 자리를 대신한다 (§10.1, T32) */
  mode: ReadingMode;
}

/**
 * 이야기 속 시각·장소 머리글 (DESIGN.md §5.4, §10.1).
 *
 * ```
 * T47 · 2026. 09. 26. 토요일 23:40. 에미야 저택
 * ```
 *
 * 시각은 **그 장면이 시작한 시각**이다. 이어지는 턴에서 분이 조금씩 흐르지만 머리글은 그대로다
 * (분까지 비교하면 매 턴 뜬다 — `storyClock.ts` 참고).
 *
 * 언제 뜨는지는 `storyClock.ts`의 `storyClockHeaders`가 정한다 — 날짜나 장소가 바뀐 자리에만 뜬다.
 * 소설형에서는 **턴 구분선(T32) 자리를 대신한다**(좌우로 선을 나누고 그 사이에 글을 둔다).
 * 말풍선형에서는 가운데 칩으로 띄운다. 두 모드 다 `role="separator"`다 — 장면을 가르는 자리이고,
 * 그래야 `aria-label`(머리글 한 줄)이 읽힌다(이름 없는 `div`의 `aria-label`은 무시된다).
 *
 * 좁은 화면(360px)에서 잘리지 않게 **글 자체가 줄바꿈된다**: 조각마다 span을 두고 `flex-wrap`으로
 * 감싸며, 칩에는 `max-w-full`을, 소설형 좌우 선에는 `flex-1`(basis 0)을 둬 글에 자리를 내준다.
 * 내용만큼 폭을 차지하는 요소를 화면 끝에 두면 잘린다(T36 회귀).
 */
export default function StoryClockHeader({ data, mode }: StoryClockHeaderProps) {
  const label = (
    <span className="flex flex-wrap items-baseline justify-center gap-x-1.5 gap-y-0.5 min-w-0 text-[11px] leading-snug tracking-wide text-text-muted">
      <span className="font-medium tabular-nums text-text-secondary">T{data.turn}</span>
      <span aria-hidden="true">·</span>
      <span className="tabular-nums">{data.when}</span>
      <span className="tabular-nums text-text-secondary">
        {data.time}
        {data.place ? '.' : ''}
      </span>
      {data.place && <span className="break-words text-text-secondary">{data.place}</span>}
    </span>
  );

  if (mode === 'novel') {
    return (
      <div className="flex items-center gap-3 mb-6" role="separator" aria-label={storyClockLine(data)}>
        <span className="h-px flex-1 min-w-4 bg-border/60" aria-hidden="true" />
        {label}
        <span className="h-px flex-1 min-w-4 bg-border/60" aria-hidden="true" />
      </div>
    );
  }

  return (
    <div className="flex justify-center mt-4 mb-2.5" role="separator" aria-label={storyClockLine(data)}>
      <span className="max-w-full rounded-full border border-border/50 bg-surface/60 px-3 py-1">{label}</span>
    </div>
  );
}
