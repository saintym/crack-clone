import { useEffect, useRef, useState } from 'react';
import { statusApi } from '../../../api/status';
import type { StoryStatus } from '../../../api/status';
import type { MemoryStatus } from '../../../api/memory';
import { errorMessage } from '../../../api/chat';
import { CharacterCard, Muted, ProtagonistCard, SceneSummary } from './StatusCards';

interface StatusPanelProps {
  storyId: number;
  /** 기억 기록 상태(useMemoryStatus). 기록이 끝났는지 알아내는 데만 쓴다 */
  memory: MemoryStatus;
}

interface Loaded {
  data: StoryStatus | null;
  error: string | null;
}

/**
 * 오른쪽 드로어의 "상태" 탭: 현재 위치·시간·동행, 주인공 카드, 인물 카드 (D13).
 * 기억 문서를 파싱한 값이라 **기록이 끝났을 때만** 바뀐다. 그래서 탭을 열 때와 기록 상태가
 * RUNNING이 아닌 새 값(DONE·REVERTED 등)으로 바뀔 때만 다시 받는다. 매 턴 받지 않는다.
 */
export default function StatusPanel({ storyId, memory }: StatusPanelProps) {
  const [loaded, setLoaded] = useState<Loaded | null>(null);
  const hasData = useRef(false);

  const running = memory.status === 'RUNNING';
  // 기록 중에는 null로 두어, 기록이 시작될 때가 아니라 끝날 때 다시 받게 한다
  const settledKey = running ? null : `${memory.lastRecordId}:${memory.status}`;

  useEffect(() => {
    // 기록 중이고 이미 받은 값이 있으면 기다린다(파일은 기록이 끝나야 바뀐다)
    if (settledKey === null && hasData.current) return;
    let cancelled = false;
    statusApi.get(storyId)
      .then(({ data }) => {
        if (cancelled) return;
        hasData.current = true;
        setLoaded({ data, error: null });
      })
      .catch((err) => {
        if (cancelled) return;
        setLoaded((prev) => ({ data: prev?.data ?? null, error: errorMessage(err, '상태를 불러오지 못했습니다') }));
      });
    return () => { cancelled = true; };
  }, [storyId, settledKey]);

  if (loaded === null) return <p className="p-3 text-[13px] text-text-muted">불러오는 중…</p>;

  const { data, error } = loaded;
  const hasMemory = data !== null && (data.recordedThroughTurn > 0 || data.characters.length > 0);

  return (
    <div className="p-3 space-y-3">
      {error && <p className="text-[13px] text-danger">{error}</p>}
      {running && (
        <p className="flex items-center gap-1.5 text-[12px] text-text-muted">
          <span className="inline-block w-2.5 h-2.5 rounded-full border-2 border-accent/30 border-t-accent animate-spin" />
          기억을 기록하는 중입니다. 끝나면 갱신됩니다.
        </p>
      )}
      {data && (
        <>
          <SceneSummary state={data.state} />
          <p className="px-1 text-[11px] text-text-muted">
            {hasMemory
              ? `턴 ${data.recordedThroughTurn}까지 기록한 기억 기준`
              : '아직 기억 기록이 없습니다. 10턴마다 자동으로 기록되고, 기록이 끝나면 여기에 반영됩니다.'}
          </p>
          {data.protagonist && <ProtagonistCard protagonist={data.protagonist} />}
          {data.characters.map((c) => <CharacterCard key={c.name} character={c} />)}
          {hasMemory && data.characters.length === 0 && (
            <p className="px-1"><Muted>기억이 기록된 인물이 없습니다.</Muted></p>
          )}
        </>
      )}
    </div>
  );
}
