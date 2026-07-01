import { useEffect, useRef, useState, useCallback } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import remarkBreaks from 'remark-breaks';
import { chatApi } from '../api/chat';
import { storyApi, type Story } from '../api/stories';
import { scenarioApi, type Scenario } from '../api/scenarios';
import { aiApi } from '../api/ai';

interface Message {
  role: 'user' | 'assistant';
  content: string;
}

export default function ChatPage() {
  const { storyId: storyIdParam } = useParams<{ storyId: string }>();
  const storyId = Number(storyIdParam);
  const navigate = useNavigate();

  // Core chat state
  const [messages, setMessages] = useState<Message[]>([]);
  const [input, setInput] = useState('');
  const [streaming, setStreaming] = useState(false);
  const [streamContent, setStreamContent] = useState('');
  const messagesEndRef = useRef<HTMLDivElement>(null);
  const inputRef = useRef<HTMLTextAreaElement>(null);

  // Sidebar & context state
  const [story, setStory] = useState<Story | null>(null);
  const [scenario, setScenario] = useState<Scenario | null>(null);
  const [stories, setStories] = useState<Story[]>([]);
  const [sidebarOpen, setSidebarOpen] = useState(false);

  // AI provider state
  const [providers, setProviders] = useState<string[]>([]);
  const [selectedProvider, setSelectedProvider] = useState<string>('');
  const [showProviderMenu, setShowProviderMenu] = useState(false);

  // Narration mode
  const [narrationMode, setNarrationMode] = useState(false);

  // Message action state
  const [menuOpenIndex, setMenuOpenIndex] = useState<number | null>(null);
  const [editingIndex, setEditingIndex] = useState<number | null>(null);
  const [editContent, setEditContent] = useState('');
  const [branchingIndex, setBranchingIndex] = useState<number | null>(null);
  const [branchTitle, setBranchTitle] = useState('');

  // Load story context
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

    aiApi.getProviders().then(({ data }) => {
      setProviders(data.available);
      setSelectedProvider(data.default);
    }).catch(console.error);
  }, [storyId]);

  // Load chat history
  useEffect(() => {
    if (!storyId) return;
    chatApi.getHistory(storyId).then(({ data }) => {
      const raw = typeof data === 'string' ? data : data.content;
      setMessages(parseHistory(raw));
    }).catch(console.error);
  }, [storyId]);

  // Auto-scroll
  useEffect(() => {
    messagesEndRef.current?.scrollIntoView({ behavior: 'smooth' });
  }, [messages, streamContent]);

  const parseHistory = (raw: string): Message[] => {
    const msgs: Message[] = [];
    const sections = raw.split(/^## (USER|ASSISTANT)$/m);
    for (let i = 1; i < sections.length; i += 2) {
      const role = sections[i].toLowerCase() as 'user' | 'assistant';
      const content = sections[i + 1]?.trim();
      if (content) msgs.push({ role, content });
    }
    return msgs;
  };

  const refreshStories = useCallback(() => {
    if (story) {
      storyApi.list(story.scenarioId).then(({ data }) => setStories(data));
    }
  }, [story]);

  const streamSSE = useCallback(async (url: string, body: object): Promise<string> => {
    const token = localStorage.getItem('crack-token');
    const response = await fetch(url, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        ...(token ? { Authorization: `Bearer ${token}` } : {}),
      },
      body: JSON.stringify(body),
    });

    if (!response.ok) throw new Error('Stream failed');

    const reader = response.body?.getReader();
    const decoder = new TextDecoder();
    let fullResponse = '';
    let doneData = '';
    let buffer = '';

    while (reader) {
      const { done, value } = await reader.read();
      if (done) break;

      buffer += decoder.decode(value, { stream: true });
      const lines = buffer.split('\n');
      buffer = lines.pop() || '';

      let currentEvent = '';
      let deltaLineCount = 0;
      for (const line of lines) {
        if (line.startsWith('event:')) {
          currentEvent = line.slice(6).trim();
          deltaLineCount = 0;
        } else if (line.startsWith('data:')) {
          const data = line.slice(5);
          if (currentEvent === 'delta') {
            // SSE splits multiline data into multiple data: lines
            // Restore newlines between them
            if (deltaLineCount > 0) fullResponse += '\n';
            fullResponse += data;
            deltaLineCount++;
            setStreamContent(fullResponse);
          } else if (currentEvent === 'done') {
            // Accumulate done data (may span multiple data: lines)
            doneData += (doneData ? '\n' : '') + data;
          }
          // Don't reset currentEvent — SSE events can have multiple data: lines
        } else if (line.trim() === '') {
          // Empty line marks end of SSE event
          currentEvent = '';
          deltaLineCount = 0;
        }
      }
    }

    // Use accumulated delta content; fall back to done data if deltas were empty
    return fullResponse || doneData;
  }, []);

  const sendMessage = useCallback(async () => {
    if (!input.trim() || streaming || !storyId) return;

    let userMsg = input.trim();
    if (narrationMode) {
      userMsg = `**${userMsg}**`;
    }
    setInput('');
    setNarrationMode(false);
    setMessages((prev) => [...prev, { role: 'user', content: userMsg }]);
    setStreaming(true);
    setStreamContent('');

    try {
      const fullResponse = await streamSSE(chatApi.streamUrl(storyId), {
        message: userMsg,
        ...(selectedProvider ? { provider: selectedProvider } : {}),
      });

      setStreamContent('');
      if (fullResponse.trim()) {
        setMessages((prev) => [...prev, { role: 'assistant', content: fullResponse }]);
        await chatApi.complete(storyId, fullResponse);
      }
      refreshStories();
    } catch (err) {
      console.error('Chat error:', err);
      setStreamContent('');
    } finally {
      setStreaming(false);
    }
  }, [input, streaming, storyId, selectedProvider, narrationMode, streamSSE, refreshStories]);

  // --- Message actions ---

  const handleRegenerate = useCallback(async (messageIndex: number) => {
    if (streaming || !storyId) return;
    setMenuOpenIndex(null);
    setStreaming(true);
    setStreamContent('');

    // Remove the assistant message from UI
    setMessages((prev) => prev.filter((_, i) => i !== messageIndex));

    try {
      const fullResponse = await streamSSE(chatApi.regenerateUrl(storyId), {
        message: '',
        ...(selectedProvider ? { provider: selectedProvider } : {}),
      });

      setStreamContent('');
      if (fullResponse.trim()) {
        setMessages((prev) => [...prev, { role: 'assistant', content: fullResponse }]);
        await chatApi.complete(storyId, fullResponse);
      }
      refreshStories();
    } catch (err) {
      console.error('Regenerate error:', err);
      setStreamContent('');
      // Reload history to restore consistent state
      chatApi.getHistory(storyId).then(({ data }) => {
        const raw = typeof data === 'string' ? data : data.content;
        setMessages(parseHistory(raw));
      });
    } finally {
      setStreaming(false);
    }
  }, [streaming, storyId, selectedProvider, streamSSE, refreshStories]);

  const handleContinue = useCallback(async () => {
    if (streaming || !storyId) return;
    setStreaming(true);
    setStreamContent('');

    try {
      const fullResponse = await streamSSE(chatApi.continueUrl(storyId), {
        message: '',
        ...(selectedProvider ? { provider: selectedProvider } : {}),
      });

      setStreamContent('');
      if (fullResponse.trim()) {
        // Add the hidden "계속" user message + new assistant response
        setMessages((prev) => [
          ...prev,
          { role: 'user', content: '계속 이어서 작성해주세요.' },
          { role: 'assistant', content: fullResponse },
        ]);
        await chatApi.complete(storyId, fullResponse);
      }
      refreshStories();
    } catch (err) {
      console.error('Continue error:', err);
      setStreamContent('');
    } finally {
      setStreaming(false);
    }
  }, [streaming, storyId, selectedProvider, streamSSE, refreshStories]);

  const handleEditStart = useCallback((messageIndex: number) => {
    setMenuOpenIndex(null);
    setEditingIndex(messageIndex);
    setEditContent(messages[messageIndex].content);
  }, [messages]);

  const handleEditSave = useCallback(async () => {
    if (editingIndex === null || !storyId) return;
    try {
      await chatApi.editMessage(storyId, editingIndex, editContent);
      setMessages((prev) =>
        prev.map((msg, i) => i === editingIndex ? { ...msg, content: editContent } : msg)
      );
    } catch (err) {
      console.error('Edit error:', err);
    } finally {
      setEditingIndex(null);
      setEditContent('');
    }
  }, [editingIndex, editContent, storyId]);

  const handleDelete = useCallback(async (messageIndex: number) => {
    if (!storyId) return;
    setMenuOpenIndex(null);
    try {
      await chatApi.deleteMessage(storyId, messageIndex);
      setMessages((prev) => prev.slice(0, messageIndex));
      refreshStories();
    } catch (err) {
      console.error('Delete error:', err);
    }
  }, [storyId, refreshStories]);

  const handleBranch = useCallback(async () => {
    if (branchingIndex === null || !branchTitle.trim() || !storyId) return;
    try {
      const { data: newStory } = await storyApi.branch(storyId, branchingIndex, branchTitle.trim());
      setBranchingIndex(null);
      setBranchTitle('');
      refreshStories();
      navigate(`/chat/${newStory.id}`);
    } catch (err) {
      console.error('Branch error:', err);
    }
  }, [branchingIndex, branchTitle, storyId, refreshStories, navigate]);

  const handleKeyDown = (e: React.KeyboardEvent) => {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault();
      sendMessage();
    }
  };

  const autoResize = (el: HTMLTextAreaElement) => {
    el.style.height = 'auto';
    el.style.height = Math.min(el.scrollHeight, 120) + 'px';
  };

  return (
    <div className="flex h-full bg-bg-primary">
      {/* Sidebar overlay for mobile */}
      {sidebarOpen && (
        <div
          className="fixed inset-0 z-30 bg-black/50 lg:hidden"
          onClick={() => setSidebarOpen(false)}
        />
      )}

      {/* Left Sidebar */}
      <aside
        className={`fixed z-40 top-0 left-0 h-full w-72 bg-bg-secondary border-r border-border/50 flex flex-col transition-transform lg:static lg:translate-x-0 ${
          sidebarOpen ? 'translate-x-0' : '-translate-x-full'
        }`}
      >
        <div className="flex items-center justify-between px-4 py-4 border-b border-border/50">
          <span className="text-sm font-medium text-text-secondary">채팅 내역</span>
          <button
            onClick={() => setSidebarOpen(false)}
            className="lg:hidden text-text-muted hover:text-text-primary p-1"
          >
            <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
              <path d="M18 6L6 18M6 6l12 12" />
            </svg>
          </button>
        </div>

        <div className="flex-1 overflow-y-auto">
          {stories.map((s) => (
            <button
              key={s.id}
              onClick={() => {
                navigate(`/chat/${s.id}`);
                setSidebarOpen(false);
              }}
              className={`w-full text-left px-4 py-3.5 border-b border-border/20 transition-all ${
                s.id === storyId
                  ? 'bg-surface-active'
                  : 'hover:bg-surface-hover'
              }`}
            >
              <div className="text-[14px] font-medium text-text-primary truncate">{s.title}</div>
              <div className="text-[12px] text-text-muted mt-1 truncate">
                {s.turnCount === 0 ? '새 채팅방' : `${s.turnCount}턴`}
              </div>
            </button>
          ))}
        </div>

        {scenario && (
          <div className="p-3 border-t border-border/50">
            <button
              onClick={() => navigate(`/stories/${scenario.name}`)}
              className="w-full py-2.5 text-sm text-accent hover:text-accent-hover bg-accent-soft rounded-xl font-medium transition-all"
            >
              + 새 스토리
            </button>
          </div>
        )}
      </aside>

      {/* Main Chat Area */}
      <div className="flex-1 flex flex-col min-w-0">
        {/* Header */}
        <header className="safe-top flex items-center justify-between px-4 py-3 bg-bg-secondary/80 backdrop-blur-md border-b border-border/50 shrink-0">
          <div className="flex items-center gap-3 min-w-0">
            <button
              onClick={() => setSidebarOpen(true)}
              className="lg:hidden text-text-secondary hover:text-text-primary p-1 -ml-1"
            >
              <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round">
                <path d="M3 12h18M3 6h18M3 18h18" />
              </svg>
            </button>
            <button
              onClick={() => navigate(scenario ? `/stories/${scenario.name}` : '/')}
              className="hidden lg:block text-text-secondary hover:text-text-primary transition-colors p-1 -ml-1"
            >
              <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round">
                <path d="M15 18l-6-6 6-6" />
              </svg>
            </button>
            <h1 className="text-[16px] font-semibold text-text-primary truncate">
              {story?.title || '채팅'}
            </h1>
          </div>

          {/* AI Provider Selector */}
          <div className="relative">
            <button
              onClick={() => setShowProviderMenu(!showProviderMenu)}
              className="flex items-center gap-1.5 px-3 py-1.5 rounded-full bg-accent-soft text-accent text-[13px] font-medium hover:bg-accent/20 transition-all"
            >
              <svg width="12" height="12" viewBox="0 0 24 24" fill="currentColor">
                <path d="M13 10V3L4 14h7v7l9-11h-7z" />
              </svg>
              {selectedProvider || 'AI'}
              <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5">
                <path d="M6 9l6 6 6-6" />
              </svg>
            </button>
            {showProviderMenu && (
              <>
                <div className="fixed inset-0 z-40" onClick={() => setShowProviderMenu(false)} />
                <div className="absolute right-0 top-full mt-2 z-50 bg-bg-tertiary border border-border/60 rounded-xl shadow-xl overflow-hidden min-w-[180px]">
                  {providers.map((p) => (
                    <button
                      key={p}
                      onClick={() => {
                        setSelectedProvider(p);
                        setShowProviderMenu(false);
                      }}
                      className={`w-full text-left px-4 py-3 text-sm transition-all ${
                        p === selectedProvider
                          ? 'bg-accent-soft text-accent font-medium'
                          : 'text-text-secondary hover:bg-surface-hover'
                      }`}
                    >
                      {p}
                    </button>
                  ))}
                </div>
              </>
            )}
          </div>
        </header>

        {/* Messages */}
        <div className="flex-1 overflow-y-auto px-4 py-5 space-y-5 bg-bg-chat">
          {messages.length === 0 && !streaming && (
            <div className="flex flex-col items-center justify-center h-full text-text-muted">
              <svg width="48" height="48" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.2" className="mb-3 opacity-30">
                <path d="M21 15a2 2 0 01-2 2H7l-4 4V5a2 2 0 012-2h14a2 2 0 012 2z" />
              </svg>
              <p className="text-sm">대화를 시작하세요</p>
            </div>
          )}

          {messages.map((msg, i) => (
            <div key={i}>
              {editingIndex === i ? (
                <div className="flex justify-start pr-10">
                  <div className="max-w-[85%] w-full">
                    <textarea
                      value={editContent}
                      onChange={(e) => setEditContent(e.target.value)}
                      className="w-full p-4 bg-surface border border-accent/60 rounded-2xl text-text-primary text-[15px] font-mono resize-none focus:outline-none leading-relaxed min-h-[120px]"
                    />
                    <div className="flex gap-2 mt-2">
                      <button
                        onClick={() => { setEditingIndex(null); setEditContent(''); }}
                        className="px-4 py-2 text-sm bg-surface hover:bg-surface-hover text-text-secondary rounded-xl transition-all"
                      >
                        취소
                      </button>
                      <button
                        onClick={handleEditSave}
                        className="px-4 py-2 text-sm bg-accent hover:bg-accent-hover text-white rounded-xl font-medium transition-all"
                      >
                        저장
                      </button>
                    </div>
                  </div>
                </div>
              ) : (
                <ChatBubble role={msg.role} content={msg.content} />
              )}

              {/* Message actions for assistant messages */}
              {msg.role === 'assistant' && editingIndex !== i && !streaming && (
                <div className="flex items-center gap-1 mt-1.5 pl-1">
                  {/* Regenerate & Continue buttons — last assistant message only */}
                  {i === messages.length - 1 && (
                    <>
                      <button
                        onClick={() => handleRegenerate(i)}
                        title="재생성"
                        className="w-7 h-7 flex items-center justify-center rounded-lg text-text-muted hover:text-text-secondary hover:bg-surface-hover transition-all"
                      >
                        <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                          <path d="M1 4v6h6M23 20v-6h-6" />
                          <path d="M20.49 9A9 9 0 005.64 5.64L1 10m22 4l-4.64 4.36A9 9 0 013.51 15" />
                        </svg>
                      </button>
                      <button
                        onClick={handleContinue}
                        title="이어하기"
                        className="w-7 h-7 flex items-center justify-center rounded-lg text-text-muted hover:text-text-secondary hover:bg-surface-hover transition-all"
                      >
                        <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                          <path d="M5 12h14M12 5l7 7-7 7" />
                        </svg>
                      </button>
                    </>
                  )}

                  {/* Menu button */}
                  <div className="relative">
                    <button
                      onClick={() => setMenuOpenIndex(menuOpenIndex === i ? null : i)}
                      className="w-7 h-7 flex items-center justify-center rounded-lg text-text-muted hover:text-text-secondary hover:bg-surface-hover transition-all"
                    >
                      <svg width="16" height="16" viewBox="0 0 24 24" fill="currentColor">
                        <circle cx="12" cy="5" r="2" />
                        <circle cx="12" cy="12" r="2" />
                        <circle cx="12" cy="19" r="2" />
                      </svg>
                    </button>
                    {menuOpenIndex === i && (
                      <>
                        <div className="fixed inset-0 z-40" onClick={() => setMenuOpenIndex(null)} />
                        <div className="absolute left-0 bottom-full mb-1 z-50 bg-bg-tertiary border border-border/60 rounded-xl shadow-xl overflow-hidden min-w-[120px]">
                          <button
                            onClick={() => { setMenuOpenIndex(null); setBranchingIndex(i); setBranchTitle(`${story?.title || '스토리'} - 분기`); }}
                            className="w-full text-left px-4 py-2.5 text-sm text-text-secondary hover:bg-surface-hover transition-all flex items-center gap-2"
                          >
                            <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                              <path d="M6 3v12M18 9a3 3 0 100-6 3 3 0 000 6zM6 21a3 3 0 100-6 3 3 0 000 6zM18 9a9 9 0 01-9 9" />
                            </svg>
                            분기
                          </button>
                          <button
                            onClick={() => handleEditStart(i)}
                            className="w-full text-left px-4 py-2.5 text-sm text-text-secondary hover:bg-surface-hover transition-all flex items-center gap-2"
                          >
                            <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                              <path d="M11 4H4a2 2 0 00-2 2v14a2 2 0 002 2h14a2 2 0 002-2v-7" />
                              <path d="M18.5 2.5a2.121 2.121 0 013 3L12 15l-4 1 1-4 9.5-9.5z" />
                            </svg>
                            수정
                          </button>
                          <button
                            onClick={() => handleDelete(i)}
                            className="w-full text-left px-4 py-2.5 text-sm text-danger hover:bg-surface-hover transition-all flex items-center gap-2"
                          >
                            <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                              <path d="M3 6h18M8 6V4a2 2 0 012-2h4a2 2 0 012 2v2M19 6l-1 14a2 2 0 01-2 2H8a2 2 0 01-2-2L5 6" />
                            </svg>
                            삭제
                          </button>
                        </div>
                      </>
                    )}
                  </div>
                </div>
              )}
            </div>
          ))}

          {streaming && streamContent && (
            <ChatBubble role="assistant" content={streamContent} isStreaming />
          )}

          <div ref={messagesEndRef} />
        </div>

        {/* Input */}
        <div className="safe-bottom shrink-0 border-t border-border/50 bg-bg-secondary/80 backdrop-blur-md px-4 py-3">
          <div className="flex items-end gap-2">
            <button
              onClick={() => setNarrationMode(!narrationMode)}
              title="상황서술 모드"
              className={`w-10 h-10 flex items-center justify-center rounded-xl shrink-0 transition-all text-[15px] font-bold ${
                narrationMode
                  ? 'bg-accent text-white'
                  : 'bg-surface hover:bg-surface-hover text-text-muted'
              }`}
            >
              **
            </button>
            <textarea
              ref={inputRef}
              value={input}
              onChange={(e) => {
                setInput(e.target.value);
                autoResize(e.target);
              }}
              onKeyDown={handleKeyDown}
              placeholder={narrationMode ? '상황을 서술하세요...' : '메시지를 입력하세요...'}
              rows={1}
              disabled={streaming}
              className={`flex-1 px-4 py-2.5 bg-surface border rounded-2xl text-text-primary placeholder-text-muted resize-none focus:outline-none transition-all text-[15px] ${
                narrationMode
                  ? 'border-accent/60 italic'
                  : 'border-border/60 focus:border-accent/60'
              }`}
              style={{ maxHeight: '120px' }}
            />
            <button
              onClick={sendMessage}
              disabled={!input.trim() || streaming}
              className="w-10 h-10 flex items-center justify-center rounded-full bg-accent hover:bg-accent-hover disabled:opacity-30 text-white transition-all shrink-0"
            >
              {streaming ? (
                <div className="w-4 h-4 border-2 border-white border-t-transparent rounded-full animate-spin" />
              ) : (
                <svg width="18" height="18" viewBox="0 0 24 24" fill="currentColor">
                  <path d="M2.01 21L23 12 2.01 3 2 10l15 2-15 2z" />
                </svg>
              )}
            </button>
          </div>
        </div>
      </div>

      {/* Branch Modal */}
      {branchingIndex !== null && (
        <div className="fixed inset-0 z-50 flex items-end justify-center bg-black/50 backdrop-blur-sm" onClick={() => setBranchingIndex(null)}>
          <div
            className="w-full max-w-lg bg-bg-secondary rounded-t-3xl px-6 pt-5 pb-8 safe-bottom border-t border-border/50"
            onClick={(e) => e.stopPropagation()}
          >
            <div className="w-10 h-1 bg-border-light/50 rounded-full mx-auto mb-6" />
            <h2 className="text-lg font-semibold text-text-primary mb-2">스토리 분기</h2>
            <p className="text-sm text-text-muted mb-5">현재 메시지까지의 내용으로 새로운 스토리를 만듭니다.</p>
            <input
              type="text"
              value={branchTitle}
              onChange={(e) => setBranchTitle(e.target.value)}
              placeholder="새 스토리 제목"
              autoFocus
              onKeyDown={(e) => e.key === 'Enter' && handleBranch()}
              className="w-full px-5 py-4 bg-surface border border-border rounded-2xl text-text-primary placeholder-text-muted focus:outline-none focus:border-accent/60 transition-all text-[15px]"
            />
            <div className="flex gap-3 mt-5">
              <button
                onClick={() => setBranchingIndex(null)}
                className="flex-1 py-3.5 bg-surface hover:bg-surface-hover text-text-secondary rounded-2xl font-medium transition-all text-[15px]"
              >
                취소
              </button>
              <button
                onClick={handleBranch}
                disabled={!branchTitle.trim()}
                className="flex-1 py-3.5 bg-accent hover:bg-accent-hover disabled:opacity-30 text-white rounded-2xl font-semibold transition-all text-[15px]"
              >
                분기
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}

function ChatBubble({ role, content, isStreaming }: { role: string; content: string; isStreaming?: boolean }) {
  const isUser = role === 'user';

  // Strip emotion tags completely — never display them
  let displayContent = content.replace(/\[감정:\s*[^\]]+\]\s*/g, '');
  // During streaming, hide partial emotion tags at the start (e.g. "[감정:" not yet closed)
  if (isStreaming) {
    displayContent = displayContent.replace(/^\[감정:[^\]]*$/, '');
  }

  return (
    <div className={`flex ${isUser ? 'justify-end pl-10' : 'justify-start pr-10'}`}>
      <div
        className={`max-w-[85%] rounded-2xl px-4 py-3.5 ${
          isUser
            ? 'bg-user-bubble text-text-primary rounded-br-sm'
            : 'bg-ai-bubble border border-border/40 text-text-primary rounded-bl-sm'
        }`}
      >
        <div className="chat-markdown text-[15px] leading-[1.7] break-words">
          <ReactMarkdown
            remarkPlugins={[remarkGfm, remarkBreaks]}
            components={{
              img: ({ src, alt }) => (
                <img
                  src={src}
                  alt={alt || ''}
                  className="max-w-full rounded-xl my-2 border border-border/30"
                  loading="lazy"
                />
              ),
              a: ({ href, children }) => (
                <a
                  href={href}
                  target="_blank"
                  rel="noopener noreferrer"
                  className="text-accent hover:text-accent-hover underline"
                >
                  {children}
                </a>
              ),
            }}
          >
            {displayContent}
          </ReactMarkdown>
          {isStreaming && (
            <span className="inline-block w-0.5 h-4 bg-accent ml-0.5 animate-pulse align-middle" />
          )}
        </div>
      </div>
    </div>
  );
}
