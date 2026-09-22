import { useCallback, useEffect, useState } from 'react';
import { directiveApi } from '../../../api/directives';
import type { Directive } from '../../../api/directives';
import { errorMessage } from '../../../api/chat';

interface Loaded {
  storyId: number;
  list: Directive[] | null;
  error: string | null;
}

/** 지시 패널과 탭 머리(켜진 개수)가 함께 쓰는 지속 지시 상태 */
export interface DirectivesState {
  /** 아직 받지 못했으면 null */
  list: Directive[] | null;
  /** 목록을 받지 못했을 때의 문구 */
  error: string | null;
  enabledCount: number;
  /** 다시 받는다 (`/ooc 내용`으로 추가한 뒤) */
  reload: () => Promise<void>;
  /** 아래 함수들은 성공하면 목록을 고치고 null, 실패하면 오류 문구를 돌려준다 */
  add: (text: string) => Promise<string | null>;
  update: (id: string, patch: { text?: string; enabled?: boolean }) => Promise<string | null>;
  remove: (id: string) => Promise<string | null>;
}

/**
 * 스토리의 지속 OOC 지시(DESIGN.md §8.1, D10). 스토리를 열 때 한 번 받고, 바꿀 때마다 서버 응답으로 목록을 고친다.
 * 스토리를 옮긴 뒤 늦게 온 응답은 버린다.
 */
export function useDirectives(storyId: number): DirectivesState {
  const [loaded, setLoaded] = useState<Loaded | null>(null);

  const fetchList = useCallback((sid: number) =>
    directiveApi.list(sid)
      .then(({ data }) => setLoaded({ storyId: sid, list: data, error: null }))
      .catch((err) => setLoaded((prev) => ({
        storyId: sid,
        // 다시 받기에 실패하면 알던 목록은 그대로 둔다
        list: prev?.storyId === sid ? prev.list : null,
        error: errorMessage(err, '지시 목록을 불러오지 못했습니다'),
      }))),
  []);

  useEffect(() => {
    if (!storyId) return;
    fetchList(storyId);
  }, [storyId, fetchList]);

  const reload = useCallback(() => fetchList(storyId), [storyId, fetchList]);

  /** 이 스토리의 목록을 고친다 (다른 스토리로 옮겼으면 무시) */
  const edit = useCallback((sid: number, change: (list: Directive[]) => Directive[]) => {
    setLoaded((prev) => (prev?.storyId === sid && prev.list ? { ...prev, list: change(prev.list), error: null } : prev));
  }, []);

  const add = useCallback((text: string) => {
    const sid = storyId;
    return directiveApi.create(sid, text)
      .then(({ data }) => { edit(sid, (list) => [...list, data]); return null; })
      .catch((err) => errorMessage(err, '지시를 추가하지 못했습니다'));
  }, [storyId, edit]);

  const update = useCallback((id: string, patch: { text?: string; enabled?: boolean }) => {
    const sid = storyId;
    return directiveApi.update(sid, id, patch)
      .then(({ data }) => { edit(sid, (list) => list.map((d) => (d.id === id ? data : d))); return null; })
      .catch((err) => errorMessage(err, '지시를 바꾸지 못했습니다'));
  }, [storyId, edit]);

  const remove = useCallback((id: string) => {
    const sid = storyId;
    return directiveApi.remove(sid, id)
      .then(() => { edit(sid, (list) => list.filter((d) => d.id !== id)); return null; })
      .catch((err) => errorMessage(err, '지시를 삭제하지 못했습니다'));
  }, [storyId, edit]);

  const current = loaded?.storyId === storyId ? loaded : null;
  const list = current?.list ?? null;

  return {
    list,
    error: current?.error ?? null,
    enabledCount: list?.filter((d) => d.enabled).length ?? 0,
    reload, add, update, remove,
  };
}
