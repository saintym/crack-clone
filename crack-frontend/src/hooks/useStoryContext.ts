import { useCallback, useEffect, useState } from 'react';
import { storyApi, type Story } from '../api/stories';
import { scenarioApi, type Scenario } from '../api/scenarios';

/** 현재 스토리, 소속 시나리오, 같은 시나리오의 스토리 목록(사이드바) */
export function useStoryContext(storyId: number) {
  const [story, setStory] = useState<Story | null>(null);
  const [scenario, setScenario] = useState<Scenario | null>(null);
  const [stories, setStories] = useState<Story[]>([]);

  useEffect(() => {
    if (!storyId) return;

    storyApi.getById(storyId).then(({ data }) => {
      setStory(data);
      scenarioApi.list().then(({ data: scenarios }) => {
        const sc = scenarios.find(s => s.id === data.scenarioId);
        if (sc) setScenario(sc);
      });
      storyApi.list(data.scenarioId).then(({ data: st }) => setStories(st));
    }).catch(console.error);
  }, [storyId]);

  const refreshStories = useCallback(() => {
    if (story) {
      storyApi.list(story.scenarioId).then(({ data }) => setStories(data));
    }
  }, [story]);

  return { story, scenario, stories, refreshStories };
}
