import { useCallback, useState } from 'react';
import { commandApi } from '../../api/commands';
import type { CommandInfo } from '../../api/commands';

/**
 * 자동완성용 명령 목록(`GET /commands`). 처음 `/`를 입력할 때 받고, `/`를 새로 입력할 때마다 다시 받는다
 * (기억 패널에서 `commands.md`를 고치면 다음 입력부터 반영된다). 스토리를 옮기기 전의 응답은 쓰지 않는다.
 */
export function useCommandList(storyId: number) {
  const [loaded, setLoaded] = useState<{ storyId: number; commands: CommandInfo[] } | null>(null);

  const refresh = useCallback((): Promise<CommandInfo[]> => {
    const sid = storyId;
    return commandApi.list(sid).then(({ data }) => {
      setLoaded({ storyId: sid, commands: data });
      return data;
    });
  }, [storyId]);

  const commands = loaded?.storyId === storyId ? loaded.commands : null;

  /** 받아 둔 목록이 있으면 그것을, 없으면 받아서 돌려준다 (실패하면 reject) */
  const ensure = useCallback(
    (): Promise<CommandInfo[]> => (commands ? Promise.resolve(commands) : refresh()),
    [commands, refresh],
  );

  return { commands, refresh, ensure };
}

/** 입력 중인 이름(`/` 뒤)으로 거른다. 앞부분이 맞는 것을 먼저, 그다음 이름이나 설명에 들어 있는 것 */
export function filterCommands(commands: CommandInfo[], query: string): CommandInfo[] {
  const q = query.toLowerCase();
  if (!q) return commands;
  const prefix = commands.filter((c) => c.name.toLowerCase().startsWith(q));
  const rest = commands.filter((c) => !prefix.includes(c) && (c.name.toLowerCase().includes(q) || c.description.toLowerCase().includes(q)));
  return [...prefix, ...rest];
}

/** 자동완성 항목의 DOM id (입력창의 aria-activedescendant용) */
export const commandOptionId = (index: number) => `command-option-${index}`;
