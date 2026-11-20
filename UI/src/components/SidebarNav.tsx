import React from 'react';
import { Globe, ArrowUpDown, PauseCircle, Repeat, Settings, ShieldCheck } from 'lucide-react';
import { useProxy } from '../context/ProxyContext';
import { AppRoute } from '../types/proxy';
import { BrandLogo } from './BrandLogo';

interface SidebarItem {
  id: AppRoute;
  label: string;
  icon: React.ComponentType<{ className?: string }>;
  matchRoutes: AppRoute[];
}

const SIDEBAR_ITEMS: SidebarItem[] = [
  { id: 'browser', label: 'Browser', icon: Globe, matchRoutes: ['browser'] },
  { id: 'traffic', label: 'Traffic', icon: ArrowUpDown, matchRoutes: ['traffic', 'traffic-detail'] },
  { id: 'intercept', label: 'Intercept', icon: PauseCircle, matchRoutes: ['intercept'] },
  { id: 'repeater', label: 'Repeater', icon: Repeat, matchRoutes: ['repeater'] },
  { id: 'settings', label: 'Settings', icon: Settings, matchRoutes: ['settings', 'https-ca'] },
];

export const SidebarNav: React.FC = () => {
  const {
    route,
    navigate,
    totalPacketsCount,
    interceptQueue,
    repeaterTabs,
    settings,
    updateSetting,
  } = useProxy();

  return (
    <aside className="hidden lg:flex flex-col w-64 shrink-0 bg-[#0a0e13] border-r border-[#262a30] h-screen sticky top-0 select-none">
      {/* Brand Header */}
      <div className="h-16 px-4 flex items-center gap-3 border-b border-[#262a30]/70">
        <BrandLogo className="w-8 h-8" />
        <div className="flex flex-col min-w-0">
          <span className="font-mono text-[10px] text-[#4cd7f6] tracking-widest uppercase truncate font-semibold">
            NetProxy Security
          </span>
          <span className="text-[15px] font-semibold text-[#e0e2ea] truncate">
            Mobile Burp
          </span>
        </div>
      </div>

      {/* Navigation Links */}
      <nav aria-label="Workspace Navigation" className="flex-1 px-3 py-4 flex flex-col gap-1">
        {SIDEBAR_ITEMS.map((item) => {
          const isActive = item.matchRoutes.includes(route);
          const Icon = item.icon;
          let countBadge: string | null = null;
          if (item.id === 'traffic') countBadge = String(totalPacketsCount);
          if (item.id === 'intercept' && interceptQueue.length > 0)
            countBadge = String(interceptQueue.length);
          if (item.id === 'repeater') countBadge = String(repeaterTabs.length);

          return (
            <button
              key={item.id}
              type="button"
              onClick={() => navigate(item.id)}
              aria-current={isActive ? 'page' : undefined}
              className={`w-full flex items-center justify-between px-3 py-2.5 rounded-lg text-sm font-medium transition-colors cursor-pointer ${
                isActive
                  ? 'bg-[#1c2025] text-[#4cd7f6]'
                  : 'text-[#bcc9cd] hover:bg-[#181c21] hover:text-[#e0e2ea]'
              }`}
            >
              <span className="flex items-center gap-3 whitespace-nowrap">
                <Icon className="w-4 h-4 shrink-0" />
                <span>{item.label}</span>
              </span>
              {countBadge !== null && (
                <span
                  className={`font-mono text-[11px] tabular-nums ${
                    item.id === 'intercept' && interceptQueue.length > 0
                      ? 'text-[#ffb95f] font-semibold'
                      : isActive
                        ? 'text-[#4cd7f6]'
                        : 'text-[#869397]'
                  }`}
                >
                  {countBadge}
                </span>
              )}
            </button>
          );
        })}

        <div className="mt-4 pt-4 border-t border-[#262a30]/70">
          <button
            type="button"
            onClick={() => navigate('https-ca')}
            className={`w-full flex items-center justify-between px-3 py-2.5 rounded-lg text-sm font-medium transition-colors cursor-pointer ${
              route === 'https-ca'
                ? 'bg-[#1c2025] text-[#4cd7f6]'
                : 'text-[#bcc9cd] hover:bg-[#181c21] hover:text-[#e0e2ea]'
            }`}
          >
            <span className="flex items-center gap-3 whitespace-nowrap">
              <ShieldCheck className="w-4 h-4 shrink-0 text-[#4edea3]" />
              <span>HTTPS &amp; CA</span>
            </span>
            <span className="font-mono text-[10px] text-[#4edea3]">
              {settings.httpsInterception ? 'TLS 1.3' : 'OFF'}
            </span>
          </button>
        </div>
      </nav>

      {/* Proxy Daemon Toggle Footer */}
      <div className="p-3 m-3 rounded-lg bg-[#181c21] border border-[#262a30] flex flex-col gap-2.5">
        <div className="flex items-center justify-between">
          <span className="font-mono text-[10px] uppercase tracking-wider text-[#869397]">
            Daemon Listener
          </span>
          <span
            className={`font-mono text-[11px] font-semibold flex items-center gap-1.5 ${
              settings.proxyEnabled ? 'text-[#4edea3]' : 'text-[#ffb4ab]'
            }`}
          >
            <span
              className={`w-1.5 h-1.5 rounded-full ${
                settings.proxyEnabled ? 'bg-[#4edea3] animate-pulse' : 'bg-[#ffb4ab]'
              }`}
            />
            {settings.proxyEnabled ? `ON :${settings.port}` : 'STOPPED'}
          </span>
        </div>
        <div className="flex items-center justify-between text-[11px] font-mono text-[#bcc9cd] tabular-nums">
          <span>127.0.0.1</span>
          <button
            type="button"
            onClick={() => updateSetting('proxyEnabled', !settings.proxyEnabled)}
            className={`px-2 py-1 rounded text-[10px] font-semibold transition-colors cursor-pointer ${
              settings.proxyEnabled
                ? 'bg-[#00a572]/20 text-[#4edea3] hover:bg-[#00a572]/30'
                : 'bg-[#262a30] text-[#e0e2ea] hover:bg-[#31353b]'
            }`}
          >
            {settings.proxyEnabled ? 'Disable' : 'Enable'}
          </button>
        </div>
      </div>
    </aside>
  );
};
