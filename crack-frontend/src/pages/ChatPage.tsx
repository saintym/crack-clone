import { useCallback, useState } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import { storyApi } from '../api/stories';
import { errorMessage } from '../api/chat';
import { useStoryContext } from '../hooks/useStoryContext';
import { useProviders } from '../hooks/useProviders';
import { useMessages } from '../hooks/useMessages';
import { useChatStream } from '../hooks/useChatStream';
import { useImageCatalog } from '../hooks/useImageCatalog';
import { ImageCatalogContext } from '../components/chat/imageTags';
import StorySidebar from '../components/chat/StorySidebar';
import ChatHeader from '../components/chat/ChatHeader';
import MessageList from '../components/chat/MessageList';
import ChatInput from '../components/chat/ChatInput';
import BranchDialog from '../components/chat/BranchDialog';
import SidePanel from '../components/panels/SidePanel';
import { useMemoryStatus } from '../components/panels/memory/useMemoryStatus';
import { memoryPanelTab } from '../components/panels/memory/memoryPanelTab';
import MemoryBadge from '../components/panels/memory/MemoryBadge';
import { useDirectives } from '../components/panels/directives/useDirectives';
import { directivesPanelTab } from '../components/panels/directives/directivesPanelTab';
import type { SidePanelTab } from '../types/panel';

/** 채팅 화면. 상태는 훅에, 화면 조각은 components/chat에 두고 여기서는 조립만 한다. */
export default function ChatPage() {
  const { storyId: storyIdParam } = useParams<{ storyId: string }>();
  const storyId = Number(storyIdParam);
  const navigate = useNavigate();

  const { story, scenario, stories, refreshStories } = useStoryContext(storyId);
  const { providers, selectedProvider, setSelectedProvider } = useProviders(storyId);
  const chat = useMessages(storyId, refreshStories);
  const imageCatalog = useImageCatalog(storyId);
  const { stream, streaming, error: streamError, clearError: clearStreamError, send, regenerate, continueStory } =
    useChatStream({
      storyId,
      provider: selectedProvider,
      onMessage: chat.applyMessage,
      onConflict: chat.markBusy,
      onInterrupted: chat.reload,
      onTurnEnd: refreshStories,
    });

  const [sidebarOpen, setSidebarOpen] = useState(false);
  const [branching, setBranching] = useState<{ messageId: number; title: string; error?: string } | null>(null);

  // 오른쪽 패널 탭. T15(기억), T18(지시), T19(상태)가 여기에 추가한다. 비어 있으면 여는 버튼을 숨긴다.
  // 기억 뱃지: 턴이 끝나거나 메시지가 지워질 때(생성 중이 아닐 때 마지막 메시지가 바뀌면) 기록 상태를 다시 확인한다
  const memoryKey = chat.loaded && !streaming ? `${chat.messages.length}:${chat.messages.at(-1)?.id}` : null;
  const memory = useMemoryStatus(storyId, chat.memory, memoryKey);
  const directives = useDirectives(storyId);
  const panelTabs: SidePanelTab[] = [memoryPanelTab(storyId, memory), directivesPanelTab(storyId, directives)];
  const [panelOpen, setPanelOpen] = useState(false);
  const [panelTabId, setPanelTabId] = useState<string | null>(null);

  const locked = streaming || chat.busy || !chat.loaded;
  const last = chat.messages[chat.messages.length - 1];
  const canContinue = last?.role === 'ASSISTANT';

  const handleBranch = useCallback(async (title: string) => {
    if (branching === null || !title.trim() || !storyId) return;
    try {
      const { data: newStory } = await storyApi.branch(storyId, branching.messageId, title.trim());
      setBranching(null);
      refreshStories();
      navigate(`/chat/${newStory.id}`);
    } catch (err) {
      console.error('Branch error:', err);
      setBranching({ ...branching, title, error: errorMessage(err, '분기하지 못했습니다') });
    }
  }, [branching, storyId, refreshStories, navigate]);

  return (
    <div className="flex h-full bg-bg-primary">
      <StorySidebar
        open={sidebarOpen}
        onClose={() => setSidebarOpen(false)}
        stories={stories}
        currentStoryId={storyId}
        scenario={scenario}
      />

      {/* Main Chat Area */}
      <div className="flex-1 flex flex-col min-w-0">
        <ChatHeader
          title={story?.title || '채팅'}
          scenario={scenario}
          onOpenSidebar={() => setSidebarOpen(true)}
          providers={providers}
          selectedProvider={selectedProvider}
          onSelectProvider={setSelectedProvider}
          onOpenPanel={panelTabs.length > 0 ? () => setPanelOpen(true) : undefined}
          panelBadge={<MemoryBadge status={memory.status} />}
        />

        <ImageCatalogContext.Provider value={imageCatalog}>
        <MessageList
          messages={chat.messages}
          stream={stream}
          locked={locked}
          onSelectVariant={(msg, index) => chat.selectVariant(msg.id, index)}
          onRegenerate={(msg, instruction) => { regenerate(msg, instruction); }}
          onContinue={() => { continueStory(); }}
          onBranch={(msg) => setBranching({ messageId: msg.id, title: `${story?.title || '스토리'} - 분기` })}
          onEditSave={(msg, content) => chat.editMessage(msg.id, content)}
          onDelete={(msg) => chat.deleteFrom(msg.id)}
        />
        </ImageCatalogContext.Provider>

        <ChatInput
          streaming={streaming}
          locked={locked}
          canContinue={canContinue}
          onSend={send}
          onContinue={() => { continueStory(); }}
          notice={streamError ?? chat.error}
          onDismissNotice={() => { clearStreamError(); chat.clearError(); }}
        />
      </div>

      <SidePanel
        open={panelOpen}
        onClose={() => setPanelOpen(false)}
        tabs={panelTabs}
        activeTabId={panelTabId}
        onSelectTab={setPanelTabId}
      />

      {branching !== null && (
        <BranchDialog
          initialTitle={branching.title}
          error={branching.error}
          onCancel={() => setBranching(null)}
          onConfirm={handleBranch}
        />
      )}
    </div>
  );
}
