import React from 'react';
import { ArrowLeft, User } from 'lucide-react';
import { useProxy } from '../context/ProxyContext';
import { BrandLogo } from './BrandLogo';

const ROUTE_TITLES: Record<string, string> = {
  browser: 'Browser',
  traffic: 'Traffic',
  'traffic-detail': 'Traffic',
  intercept: 'Intercept',
  repeater: 'Repeater',
  settings: 'Settings',
  'https-ca': 'Https & Ca',
};

export const TopBar: React.FC = () => {
  const { route, goBack, navigate, settings } = useProxy();
  const isSubpage = route === 'traffic-detail' || route === 'https-ca';
  const title = ROUTE_TITLES[route] ?? 'Traffic';

  return (
    <header className="lg:hidden fixed top-0 inset-x-0 z-50 bg-[#0a0e13]/90 backdrop-blur-xl pt-safe border-b border-[#262a30]/60">
      <div className="h-14 px-3 flex items-center justify-between gap-2">
        <div className="flex items-center gap-2.5 min-w-0">
          {isSubpage ? (
            <button
              type="button"
              onClick={goBack}
              aria-label="Navigate back"
              className="w-9 h-9 rounded-lg bg-[#1c2025] hover:bg-[#262a30] flex items-center justify-center text-[#e0e2ea] active:scale-95 transition-all shrink-0 cursor-pointer"
            >
              <ArrowLeft className="w-4 h-4" />
            </button>
          ) : (
            <BrandLogo className="w-8 h-8" />
          )}
          <div className="flex flex-col min-w-0">
            <span className="font-mono text-[10px] text-[#4cd7f6] tracking-widest uppercase truncate font-semibold">
              {route === 'https-ca'
                ? 'SETTINGS / HTTPS & CA'
                : route === 'intercept' || route === 'repeater'
                  ? 'MOBILE BURP'
                  : 'NETPROXY SECURITY'}
            </span>
            <span className="text-[15px] font-semibold text-[#e0e2ea] truncate leading-tight">
              {title}
            </span>
          </div>
        </div>

        <div className="flex items-center gap-2 shrink-0">
          <button
            type="button"
            onClick={() => navigate('settings')}
            aria-label="Proxy Status and Profile"
            className="flex items-center gap-1.5 px-2.5 py-1 rounded-full bg-[#00a572]/15 text-[#4edea3] font-mono text-[10px] font-semibold tracking-wider cursor-pointer"
          >
            <span
              className={`w-1.5 h-1.5 rounded-full ${
                settings.proxyEnabled ? 'bg-[#4edea3] animate-pulse' : 'bg-[#ffb4ab]'
              }`}
            />
            <span className={settings.proxyEnabled ? 'text-[#4edea3]' : 'text-[#ffb4ab]'}>
              {settings.proxyEnabled ? `PROXY :${settings.port}` : 'PROXY OFF'}
            </span>
          </button>
          <button
            type="button"
            onClick={() => navigate('settings')}
            aria-label="Open Settings"
            className="w-8 h-8 rounded-full bg-[#4cd7f6] text-[#003640] flex items-center justify-center shrink-0 cursor-pointer active:scale-95 transition-transform"
          >
            <User className="w-4 h-4" />
          </button>
        </div>
      </div>
    </header>
  );
};
