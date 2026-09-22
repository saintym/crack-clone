import type { ReactNode } from 'react';
import type { CharacterStatus, ProtagonistStatus, StatusItem, StatusRelation, StoryStateInfo } from '../../../api/status';
import { eventTurnLabel, recentEvents } from './format';

/** 위쪽 요약: 현재 위치, 시간, 동행 */
export function SceneSummary({ state }: { state: StoryStateInfo }) {
  return (
    <div className="rounded-xl border border-border/50 bg-bg-primary/30 px-3 py-2.5 space-y-1.5 text-[13px]">
      <SummaryRow label="위치">{state.location || <Muted>모름</Muted>}</SummaryRow>
      <SummaryRow label="시간">{state.time || <Muted>모름</Muted>}</SummaryRow>
      <SummaryRow label="동행">
        {state.companions.length === 0 ? (
          <Muted>없음</Muted>
        ) : (
          <span className="flex flex-wrap gap-1">
            {state.companions.map((name) => (
              <span key={name} className="px-2 py-0.5 rounded-full bg-accent-soft text-accent text-[12px]">{name}</span>
            ))}
          </span>
        )}
      </SummaryRow>
    </div>
  );
}

function SummaryRow({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div className="flex gap-3">
      <span className="shrink-0 w-8 text-text-muted">{label}</span>
      <span className="min-w-0 flex-1 text-text-primary break-words">{children}</span>
    </div>
  );
}

/** 주인공 카드: 관계, 스탯·기술, 소지품, 신체 */
export function ProtagonistCard({ protagonist }: { protagonist: ProtagonistStatus }) {
  const { relations, statsAndSkills, possessions, body } = protagonist;
  const empty = relations.length + statsAndSkills.length + possessions.length + body.length === 0;
  return (
    <Card title={protagonist.name} tag="주인공">
      {empty && <Muted>아직 변화 기록이 없습니다.</Muted>}
      <RelationSection relations={relations} />
      <ItemSection title="스탯·기술" items={statsAndSkills} />
      <ItemSection title="소지품" items={possessions} />
      <ItemSection title="신체" items={body} />
    </Card>
  );
}

/** 인물 카드: 관계, 최근 사건 3개, 소지품·기술·신체 */
export function CharacterCard({ character }: { character: CharacterStatus }) {
  const events = recentEvents(character.events);
  const hidden = character.events.length - events.length;
  return (
    <Card title={character.name} tag={character.companion ? '동행' : undefined}>
      <RelationSection relations={character.relations} />
      {events.length > 0 && (
        <Section title={hidden > 0 ? `최근 사건 (전체 ${character.events.length}개)` : '최근 사건'}>
          {events.map((e, i) => {
            const turn = eventTurnLabel(e);
            return (
              <li key={i}>
                {turn && <span className="text-[11px] text-text-muted tabular-nums">{turn}</span>}{turn && ' '}
                {e.description}
              </li>
            );
          })}
        </Section>
      )}
      <ItemSection title="소지품·기술·신체" items={character.possessions} />
    </Card>
  );
}

function Card({ title, tag, children }: { title: string; tag?: string; children: ReactNode }) {
  return (
    <section className="rounded-xl border border-border/50 bg-bg-primary/30 px-3 py-2.5 space-y-2.5">
      <h3 className="flex items-center gap-2 text-[14px] font-medium text-text-primary">
        <span className="truncate">{title}</span>
        {tag && <span className="shrink-0 px-2 py-0.5 rounded-full bg-accent-soft text-accent text-[11px] font-normal">{tag}</span>}
      </h3>
      {children}
    </section>
  );
}

function Section({ title, children }: { title: string; children: ReactNode }) {
  return (
    <div>
      <h4 className="mb-1 text-[11px] font-medium text-text-muted">{title}</h4>
      <ul className="space-y-1 text-[13px] leading-relaxed text-text-secondary list-disc pl-4 marker:text-text-muted">
        {children}
      </ul>
    </div>
  );
}

function RelationSection({ relations }: { relations: StatusRelation[] }) {
  if (relations.length === 0) return null;
  return (
    <Section title="관계">
      {relations.map((r, i) => (
        <li key={i} className="whitespace-pre-line">
          {r.target && <span className="font-medium text-text-primary">{r.target}:</span>}{r.target && ' '}
          {r.description}
        </li>
      ))}
    </Section>
  );
}

function ItemSection({ title, items }: { title: string; items: StatusItem[] }) {
  if (items.length === 0) return null;
  return (
    <Section title={title}>
      {items.map((item, i) => <li key={i} className="whitespace-pre-line">{item.text}</li>)}
    </Section>
  );
}

export function Muted({ children }: { children: ReactNode }) {
  return <span className="text-[13px] text-text-muted">{children}</span>;
}
