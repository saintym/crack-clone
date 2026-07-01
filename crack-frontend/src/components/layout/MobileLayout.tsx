import { ReactNode } from 'react';

interface Props {
  children: ReactNode;
  title?: string;
  onBack?: () => void;
  rightAction?: ReactNode;
}

export default function MobileLayout({ children, title, onBack, rightAction }: Props) {
  return (
    <div className="flex flex-col h-full bg-bg-primary">
      {title && (
        <header className="safe-top flex items-center justify-between px-5 py-4 bg-bg-secondary/80 backdrop-blur-md border-b border-border/50 shrink-0">
          <div className="flex items-center gap-3">
            {onBack && (
              <button
                onClick={onBack}
                className="text-text-secondary hover:text-text-primary transition-colors p-1 -ml-1"
              >
                <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round">
                  <path d="M15 18l-6-6 6-6" />
                </svg>
              </button>
            )}
            <h1 className="text-[17px] font-semibold text-text-primary truncate">{title}</h1>
          </div>
          {rightAction && <div>{rightAction}</div>}
        </header>
      )}
      <main className="flex-1 overflow-hidden">
        {children}
      </main>
    </div>
  );
}
