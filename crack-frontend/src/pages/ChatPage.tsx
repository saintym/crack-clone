import { useCallback, useState } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import { storyApi } from '../api/stories';
import { useStoryContext } from '../hooks/useStoryContext';
import { useProviders } from '../hooks/useProviders';
import { useMessages } from '../hooks/useMessages';
import { useChatStream } from '../hooks/useChatStream';
import StorySidebar from '../components/chat/StorySidebar';
import ChatHeader from '../components/chat/ChatHeader';
import MessageList from '../components/chat/MessageList';
import ChatInput from '../components/chat/ChatInput';
import BranchDialog from '../components/chat/BranchDialog';
import SidePanel from '../components/panels/SidePanel';
import type { SidePanelTab } from '../types/panel';

/** 채팅 화면. 상태는 훅에, 화면 조각은 components/chat에 두고 여기서는 조립만 한다. */
export default function ChatPage() {
  const { storyId: storyIdParam } = useParams<{ storyId: string }>();
  const storyId = Number(storyIdParam);
  const navigate = useNavigate();

  const { story, scenario, stories, refreshStories } = useStoryContext(storyId);
  const { providers, selectedProvider, setSelectedProvider } = useProviders(storyId);
  const { messages, setMessages, reloadHistory, editMessage, deleteMessage } =
    useMessages(storyId, refreshStories);
  const { streaming, streamContent, send, regenerate, continueStory } = useChatStream({
    storyId,
    provider: selectedProvider,
    setMessages,
    reloadHistory,
    onTurnEnd: refreshStories,
  });

  const [sidebarOpen, setSidebarOpen] = useState(false);
  const [branching, setBranching] = useState<{ index: number; title: string } | null>(null);

  // 오른쪽 패널 탭. T15(기억), T18(지시), T19(상태)가 여기에 추가한다. 비어 있으면 여는 버튼을 숨긴다.
  const panelTabs: SidePanelTab[] = [];
  const [panelOpen, setPanelOpen] = useState(false);

  const handleBranch = useCallback(async (title: string) => {
    if (branching === null || !title.trim() || !storyId) return;
    try {
      const { data: newStory } = await storyApi.branch(storyId, branching.index, title.trim());
      setBranching(null);
      refreshStories();
      navigate(`/chat/${newStory.id}`);
    } catch (err) {
      console.error('Branch error:', err);
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
        />

        <MessageList
          messages={messages}
          streaming={streaming}
          streamContent={streamContent}
          onRegenerate={regenerate}
          onContinue={continueStory}
          onBranch={(index) => setBranching({ index, title: `${story?.title || '스토리'} - 분기` })}
          onEditSave={editMessage}
          onDelete={deleteMessage}
        />

        <ChatInput streaming={streaming} sendBlocked={streaming || !storyId} onSend={send} />
      </div>

      <SidePanel open={panelOpen} onClose={() => setPanelOpen(false)} tabs={panelTabs} />

      {branching !== null && (
        <BranchDialog
          initialTitle={branching.title}
          onCancel={() => setBranching(null)}
          onConfirm={handleBranch}
        />
      )}
    </div>
  );
}
