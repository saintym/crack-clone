import api from './client';
import type { Directive } from './directives';
import type { TriggerResponse } from './memory';

/** `SYSTEM`은 REST로 즉시 실행(`/기록`, `/ooc`), `CUSTOM`은 스토리의 `commands.md`에 정의한 명령 */
export type CommandType = 'SYSTEM' | 'CUSTOM';

/** `GET /commands` 항목 (DESIGN.md §8.2). `name`에는 `/`가 없다 */
export interface CommandInfo {
  name: string;
  description: string;
  type: CommandType;
}

/** `POST /commands/system` 응답. 해당하지 않는 필드는 null */
export interface SystemCommandResult {
  name: string;
  /** `기록`: `POST /memory/record`와 같은 응답 */
  record: TriggerResponse | null;
  /** `ooc`: 추가된 지시 */
  directive: Directive | null;
}

/** 시스템 명령 이름. 서버는 대소문자를 무시하고 비교한다 */
export const SYSTEM_COMMAND = { RECORD: '기록', OOC: 'ooc' } as const;

const base = (storyId: number) => `/stories/${storyId}/commands`;

/** `/` 명령 API (DESIGN.md §8.2). 모든 경로는 `/api/stories/{storyId}` 아래 */
export const commandApi = {
  /** 자동완성용 목록. 시스템 명령이 먼저, 사용자 정의 명령은 파일 순서 */
  list: (storyId: number) =>
    api.get<CommandInfo[]>(base(storyId)),

  /** 시스템 명령 실행. 메시지를 남기지 않는다. `ooc`에 `args`가 없거나 모르는 이름이면 400 */
  runSystem: (storyId: number, name: string, args?: string) =>
    api.post<SystemCommandResult>(`${base(storyId)}/system`, args ? { name, args } : { name }),
};

/** 입력한 `/명령 인자`를 나눈다. `/`로 시작하지 않거나 이름이 비면 null */
export function parseCommandInput(text: string): { name: string; args: string } | null {
  const match = /^\/(\S+)(?:\s+([\s\S]*))?$/.exec(text.trim());
  if (!match) return null;
  return { name: match[1], args: (match[2] ?? '').trim() };
}

/** 서버와 같이 대소문자를 무시하고 이름으로 찾는다 */
export function findCommand(commands: CommandInfo[], name: string): CommandInfo | undefined {
  const key = name.toLowerCase();
  return commands.find((c) => c.name.toLowerCase() === key);
}
