import React from 'react';
import { ProxyProvider, useProxy } from './context/ProxyContext';
import { TopBar } from './components/TopBar';
import { BottomNav } from './components/BottomNav';
import { SidebarNav } from './components/SidebarNav';
import { TrafficView } from './views/TrafficView';
import { RequestDetailView } from './views/RequestDetailView';
import { BrowserView } from './views/BrowserView';
import { InterceptView } from './views/InterceptView';
import { RepeaterView } from './views/RepeaterView';
import { SettingsView } from './views/SettingsView';
import { HttpsCaView } from './views/HttpsCaView';

const AppShell: React.FC = () => {
  const { route, toast } = useProxy();

  const renderMainView = () => {
    switch (route) {
      case 'browser':
        return <BrowserView />;
      case 'traffic':
        return <TrafficView />;
      case 'traffic-detail':
        return <RequestDetailView />;
      case 'intercept':
        return <InterceptView />;
      case 'repeater':
        return <RepeaterView />;
      case 'settings':
        return <SettingsView />;
      case 'https-ca':
        return <HttpsCaView />;
      default:
        return <TrafficView />;
    }
  };

  return (
    <div className="min-h-screen h-dvh w-full bg-[#101419] text-[#e0e2ea] flex overflow-hidden">
      {/* Desktop Left Navigation Rail */}
      <SidebarNav />

      {/* Mobile Top Header + Active Workspace */}
      <div className="flex-1 flex flex-col min-w-0 min-h-0 relative">
        <TopBar />

        <main className="flex-1 flex flex-col min-h-0 pt-14 pb-16 lg:pt-0 lg:pb-0 overflow-hidden">
          {renderMainView()}
        </main>

        <BottomNav />

        {/* Subtle Non-blocking Feedback Toast */}
        {toast && (
          <div
            role="status"
            aria-live="polite"
            className="fixed bottom-20 lg:bottom-5 left-1/2 -translate-x-1/2 lg:left-auto lg:right-5 lg:translate-x-0 z-50 px-3.5 py-2 rounded-lg bg-[#1c2025] border border-[#4cd7f6]/40 text-[#e0e2ea] font-mono text-xs shadow-xl whitespace-nowrap"
          >
            {toast}
          </div>
        )}
      </div>
    </div>
  );
};

export default function App() {
  return (
    <ProxyProvider>
      <AppShell />
    </ProxyProvider>
  );
}
