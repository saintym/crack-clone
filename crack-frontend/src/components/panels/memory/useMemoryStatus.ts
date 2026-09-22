import { useCallback, useEffect, useState } from 'react';
import { memoryApi } from '../../../api/memory';
import type { MemoryRecordSummary, MemoryStatus } from '../../../api/memory';

/** 기록이 도는 동안(RUNNING) 목록을 다시 받는 간격 */
const RUNNING_POLL_MS = 5000;

const NONE: MemoryStatus = { status: 'NONE', lastRecordId: null, unseen: false };

/**
 * 기록 목록으로 새로 알게 된 상태. `basis`는 이 값을 계산할 때 기준으로 삼은 서버 상태(`GET /messages`의 `story.memory`)다.
 * 서버 상태가 새로 오면(basis가 달라지면) 서버 값을 믿는다.
 */
interface LocalState extends MemoryStatus {
  storyId: number;
  basis: MemoryStatus | null;
}

interface RecordsState {
  storyId: number;
  records: MemoryRecordSummary[];
}

/**
 * 기록 목록에서 뱃지 상태를 다시 계산한다. 서버의 `unseen`(읽지 않은 DONE·FAILED가 있는지)과 같은 뜻이 되도록,
 * 이전에 알던 뒤로 새로 끝난 기록(다른 기록이거나 RUNNING이던 기록)만 읽지 않은 것으로 더한다.
 */
function fromRecords(prev: MemoryStatus, records: MemoryRecordSummary[]): MemoryStatus {
  const latest = records[0];
  if (!latest) return NONE;
  const finished = latest.status === 'DONE' || latest.status === 'FAILED';
  const newlyFinished = finished && (latest.id !== prev.lastRecordId || prev.status === 'RUNNING');
  const anyFinished = records.some((r) => r.status === 'DONE' || r.status === 'FAILED');
  return {
    status: latest.status,
    lastRecordId: latest.id,
    unseen: anyFinished && (prev.unseen || newlyFinished),
  };
}

/** 채팅 헤더 뱃지와 기억 패널이 함께 쓰는 기억 기록 상태 */
export interface MemoryState {
  storyId: number;
  /** 뱃지 상태. 아직 모르면 NONE */
  status: MemoryStatus;
  /** 기록 목록 (최근 것부터). 처음 받기 전에는 null */
  records: MemoryRecordSummary[] | null;
  /** 기록 목록을 다시 받아 상태를 갱신한다 */
  refresh: () => Promise<void>;
  /** 읽음 처리 (패널을 열었을 때) */
  markSeen: () => void;
}

/**
 * 기억 기록 상태 (D7: 채팅은 끊지 않고 작은 뱃지만).
 * - 기준값은 `GET /messages`의 `story.memory`(useMessages가 받은 것)다. 서버가 새 값을 주면 그것을 믿는다.
 * - 턴이 끝나거나 메시지가 지워지면(`refreshKey`가 바뀌면) 가벼운 `GET /memory/records`로 다시 확인한다.
 *   10턴 자동 기록은 응답 완료(`done`) 전에 RUNNING 행을 만들므로 이때 바로 보인다.
 * - RUNNING이면 5초마다 목록을 다시 받는다.
 * @param serverStatus 마지막 `GET /messages`의 `story.memory` (아직 없으면 null)
 * @param refreshKey 바뀌면 목록을 다시 받는다. null이면(생성 중) 받지 않는다
 */
export function useMemoryStatus(
  storyId: number,
  serverStatus: MemoryStatus | null,
  refreshKey: string | null,
): MemoryState {
  const [local, setLocal] = useState<LocalState | null>(null);
  const [recordsState, setRecordsState] = useState<RecordsState | null>(null);
  const [pollTick, setPollTick] = useState(0);

  const localValid = local !== null && local.storyId === storyId && local.basis === serverStatus;
  const status: MemoryStatus = localValid
    ? { status: local.status, lastRecordId: local.lastRecordId, unseen: local.unseen }
    : serverStatus ?? NONE;
  const records = recordsState?.storyId === storyId ? recordsState.records : null;

  /** 목록을 반영한다. 기준 서버 상태가 그사이 바뀌었어도 목록이 더 새것이므로 그 위에서 계산한다 */
  const applyRecords = useCallback((sid: number, basis: MemoryStatus | null, list: MemoryRecordSummary[]) => {
    setRecordsState({ storyId: sid, records: list });
    setLocal((prev) => {
      const prevStatus = prev && prev.storyId === sid && prev.basis === basis ? prev : basis ?? NONE;
      return { ...fromRecords(prevStatus, list), storyId: sid, basis };
    });
  }, []);

  const refresh = useCallback((): Promise<void> => {
    const sid = storyId;
    const basis = serverStatus;
    return memoryApi.records(sid)
      .then(({ data }) => applyRecords(sid, basis, data))
      .catch((err) => console.error('기억 기록 목록을 받지 못했습니다:', err));
  }, [storyId, serverStatus, applyRecords]);

  // 턴이 끝났거나 메시지가 지워졌을 때 (스토리를 연 직후 포함)
  useEffect(() => {
    if (!storyId || refreshKey === null) return;
    let cancelled = false;
    const basis = serverStatus;
    memoryApi.records(storyId)
      .then(({ data }) => {
        if (!cancelled) applyRecords(storyId, basis, data);
      })
      .catch((err) => console.error('기억 기록 목록을 받지 못했습니다:', err));
    return () => { cancelled = true; };
    // serverStatus는 기준값으로만 쓴다. 서버 값이 바뀔 때마다 다시 받을 필요는 없다
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [storyId, refreshKey, applyRecords]);

  // 기록이 도는 동안 목록을 다시 받는다
  const running = status.status === 'RUNNING';
  useEffect(() => {
    if (!running || !storyId) return;
    let cancelled = false;
    const basis = serverStatus;
    const timer = window.setTimeout(() => {
      memoryApi.records(storyId)
        .then(({ data }) => {
          if (cancelled) return;
          applyRecords(storyId, basis, data);
          setPollTick((t) => t + 1);
        })
        .catch(() => {
          if (!cancelled) setPollTick((t) => t + 1);
        });
    }, RUNNING_POLL_MS);
    return () => {
      cancelled = true;
      window.clearTimeout(timer);
    };
  }, [running, pollTick, storyId, serverStatus, applyRecords]);

  const markSeen = useCallback(() => {
    const sid = storyId;
    const basis = serverStatus;
    setLocal((prev) => {
      const prevStatus = prev && prev.storyId === sid && prev.basis === basis ? prev : basis ?? NONE;
      return { ...prevStatus, unseen: false, storyId: sid, basis };
    });
    memoryApi.markSeen(sid).catch((err) => console.error('기억 뱃지 읽음 처리 실패:', err));
  }, [storyId, serverStatus]);

  return { storyId, status, records, refresh, markSeen };
}
