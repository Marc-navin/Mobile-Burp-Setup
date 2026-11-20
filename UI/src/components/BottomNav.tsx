import React from 'react';
import { Globe, ArrowUpDown, PauseCircle, Repeat, Settings } from 'lucide-react';
import { useProxy } from '../context/ProxyContext';
import { AppRoute } from '../types/proxy';

interface NavItem {
  id: AppRoute;
  label: string;
  icon: React.ComponentType<{ className?: string }>;
  matchRoutes: AppRoute[];
}

const NAV_ITEMS: NavItem[] = [
  { id: 'browser', label: 'Browser', icon: Globe, matchRoutes: ['browser'] },
  { id: 'traffic', label: 'Traffic', icon: ArrowUpDown, matchRoutes: ['traffic', 'traffic-detail'] },
  { id: 'intercept', label: 'Intercept', icon: PauseCircle, matchRoutes: ['intercept'] },
  { id: 'repeater', label: 'Repeater', icon: Repeat, matchRoutes: ['repeater'] },
  { id: 'settings', label: 'Settings', icon: Settings, matchRoutes: ['settings', 'https-ca'] },
];

export const BottomNav: React.FC = () => {
  const { route, navigate, interceptQueue } = useProxy();

  return (
    <nav
      aria-label="Primary Navigation"
      className="lg:hidden fixed bottom-0 inset-x-0 z-50 pb-safe bg-[#0a0e13]/95 backdrop-blur-xl border-t border-[#262a30]/80 shadow-[0_-4px_24px_rgba(0,0,0,0.6)]"
    >
      <div className="flex items-center justify-around h-16 px-1">
        {NAV_ITEMS.map((item) => {
          const isActive = item.matchRoutes.includes(route);
          const Icon = item.icon;
          return (
            <button
              key={item.id}
              type="button"
              onClick={() => navigate(item.id)}
              aria-current={isActive ? 'page' : undefined}
              className={`relative flex flex-col items-center justify-center min-w-[56px] min-h-[44px] py-1 gap-1 transition-colors whitespace-nowrap shrink-0 cursor-pointer ${
                isActive ? 'text-[#4cd7f6]' : 'text-[#bcc9cd] hover:text-[#e0e2ea]'
              }`}
            >
              <div className="relative">
                <Icon className="w-5 h-5" />
                {item.id === 'intercept' && interceptQueue.length > 0 && (
                  <span className="absolute -top-1.5 -right-2 min-w-[15px] h-[15px] px-1 rounded-full bg-[#e79400] text-[#0a0e13] font-mono text-[9px] font-bold flex items-center justify-center tabular-nums">
                    {interceptQueue.length}
                  </span>
                )}
              </div>
              <span
                className={`font-mono text-[10px] tracking-wider ${
                  isActive ? 'font-semibold' : 'font-medium'
                }`}
              >
                {item.label}
              </span>
            </button>
          );
        })}
      </div>
    </nav>
  );
};
