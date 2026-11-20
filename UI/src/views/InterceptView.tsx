import React, { useState } from 'react';
import {
  ArrowDown,
  ArrowRight,
  ArrowUp,
  BadgeCheck,
  Edit3,
  FilePenLine,
  Hourglass,
  Key,
  PauseCircle,
  Plus,
  X,
} from 'lucide-react';
import { useProxy } from '../context/ProxyContext';
import { SyntaxCodeBlock } from '../components/SyntaxCodeBlock';

type InterceptSubTab = 'headers' | 'params' | 'body' | 'cookies' | 'raw';

export const InterceptView: React.FC = () => {
  const {
    interceptQueue,
    updateInterceptedBody,
    updateInterceptedHeader,
    forwardIntercepted,
    dropIntercepted,
    simulateIntercepted,
    settings,
    updateSetting,
  } = useProxy();

  const [subTab, setSubTab] = useState<InterceptSubTab>('headers');
  const [editingPayload, setEditingPayload] = useState(false);
  const [editingHeaders, setEditingHeaders] = useState(false);

  const current = interceptQueue[0] ?? null;

  if (!current) {
    return (
      <div className="flex-1 flex flex-col items-center justify-center p-6 text-center bg-[#101419]">
        <div className="w-14 h-14 rounded-2xl bg-[#181c21] border border-[#262a30] flex items-center justify-center text-[#4cd7f6] mb-4">
          <PauseCircle className="w-7 h-7" />
        </div>
        <h2 className="text-base font-semibold text-[#e0e2ea]">Intercept Queue Clear</h2>
        <p className="text-xs text-[#bcc9cd] mt-1 max-w-sm">
          {settings.interceptEnabled
            ? 'Relay is listening for matching outbound HTTP/HTTPS requests to pause for inspection.'
            : 'Intercept mode is currently paused. Requests are flowing directly through the proxy.'}
        </p>
        <div className="flex items-center gap-2.5 mt-5">
          <button
            type="button"
            onClick={() => updateSetting('interceptEnabled', !settings.interceptEnabled)}
            className={`px-4 py-2 rounded-lg text-xs font-semibold transition-colors cursor-pointer ${
              settings.interceptEnabled
                ? 'bg-[#e79400]/20 text-[#ffb95f] hover:bg-[#e79400]/30'
                : 'bg-[#00a572]/20 text-[#4edea3] hover:bg-[#00a572]/30'
            }`}
          >
            {settings.interceptEnabled ? 'Pause Intercept' : 'Enable Intercept'}
          </button>
          <button
            type="button"
            onClick={simulateIntercepted}
            className="px-4 py-2 rounded-lg bg-[#4cd7f6] text-[#003640] text-xs font-bold flex items-center gap-1.5 hover:bg-[#3cc7e6] transition-colors cursor-pointer"
          >
            <Plus className="w-4 h-4" />
            <span>Capture Sample Request</span>
          </button>
        </div>
      </div>
    );
  }

  const rawHttp = `${current.method} ${current.path} HTTP/1.1\n${current.headers
    .map((h) => `${h.name}: ${h.value}`)
    .join('\n')}\n\n${current.body}`;

  return (
    <div className="flex flex-col flex-1 min-h-0 w-full bg-[#101419] text-[#e0e2ea]">
      {/* Top Paused & Queue Status Strip */}
      <div className="px-3 pt-2.5 pb-2 flex flex-col gap-2 shrink-0">
        <div className="flex items-center justify-between">
          <div className="flex items-center gap-1.5">
            <span className="inline-flex items-center gap-1.5 px-2.5 py-0.5 rounded-full bg-[#e79400]/20 text-[#ffb95f] font-mono text-[10px] font-semibold uppercase tracking-wider">
              <span className="w-1.5 h-1.5 rounded-full bg-[#ffb95f] animate-pulse" />
              INTERCEPTING
            </span>
            <span className="px-2 py-0.5 rounded bg-[#262a30] text-[#bcc9cd] font-mono text-[10px] font-semibold">
              HOLDING
            </span>
          </div>
          <div className="flex items-center gap-1.5 text-[#bcc9cd] font-mono text-[11px] tabular-nums">
            <Hourglass className="w-3.5 h-3.5 text-[#ffb95f]" />
            <span>
              Queue:{' '}
              <strong className="text-[#e0e2ea]">
                {interceptQueue.length} {interceptQueue.length === 1 ? 'request' : 'requests'}
              </strong>
            </span>
          </div>
        </div>

        {/* Alert / Paused Banner Card */}
        <div className="bg-[#181c21] p-2.5 rounded-lg flex items-center justify-between gap-3">
          <div className="flex items-center gap-2.5 min-w-0">
            <div className="w-8 h-8 rounded-md bg-[#e79400]/25 flex items-center justify-center text-[#ffb95f] shrink-0">
              <PauseCircle className="w-5 h-5" />
            </div>
            <div className="flex flex-col min-w-0">
              <div className="flex items-center gap-1.5">
                <span className="font-mono text-[11px] text-[#ffb95f] font-bold tracking-wider uppercase">
                  REQUEST PAUSED
                </span>
                <span className="font-mono text-[11px] text-[#ffb95f]/80">• {current.protocol}</span>
              </div>
              <span className="text-[11px] text-[#bcc9cd] truncate">
                Relay suspended. Modify headers/body or pass forward.
              </span>
            </div>
          </div>
          <span className="px-2 py-1 rounded bg-[#ffb95f] text-[#472a00] font-mono text-[10px] font-bold tracking-tight uppercase shrink-0">
            ACTIVE
          </span>
        </div>

        {/* Intercepted Request Summary Bar */}
        <div className="bg-[#1c2025] p-2.5 rounded-lg flex flex-col gap-1">
          <div className="flex items-center justify-between gap-2">
            <div className="flex items-center gap-2 min-w-0">
              <span className="px-2 py-0.5 rounded bg-[#e79400]/25 text-[#ffb95f] font-mono text-[11px] font-bold tracking-wider">
                {current.method}
              </span>
              <span className="font-mono text-[13px] text-[#e0e2ea] font-semibold truncate">
                {current.path}
              </span>
            </div>
            <span className="font-mono text-[11px] px-2 py-0.5 rounded bg-[#4cd7f6]/15 text-[#4cd7f6] font-semibold shrink-0 tabular-nums">
              #{String(current.sequenceNumber).padStart(3, '0')}
            </span>
          </div>
          <div className="flex items-center justify-between text-[#bcc9cd] font-mono text-[11px] tabular-nums">
            <span className="truncate text-[#e0e2ea]">{current.host}</span>
            <span className="shrink-0">
              {current.sizeLabel} • {current.timestamp}
            </span>
          </div>
        </div>
      </div>

      {/* Primary Tab Segment */}
      <div className="px-3 shrink-0">
        <div className="grid grid-cols-2 p-1 bg-[#0a0e13] rounded-lg gap-1">
          <button
            type="button"
            className="py-1.5 px-3 rounded-md bg-[#262a30] text-[#4cd7f6] text-[13px] font-semibold flex items-center justify-center gap-1.5 shadow-sm"
          >
            <ArrowUp className="w-3.5 h-3.5" />
            <span>Request</span>
          </button>
          <button
            type="button"
            disabled
            className="py-1.5 px-3 rounded-md text-[#bcc9cd] text-[13px] font-medium flex items-center justify-center gap-1.5 opacity-55 cursor-not-allowed"
          >
            <ArrowDown className="w-3.5 h-3.5" />
            <span>Response (0)</span>
          </button>
        </div>
      </div>

      {/* Sub-tabs Horizontal Rail */}
      <div className="px-3 py-2 flex items-center gap-1.5 overflow-x-auto no-scrollbar shrink-0">
        {(
          [
            { id: 'headers', label: `Headers (${current.headers.length})` },
            { id: 'params', label: `Params (${current.params.length})` },
            { id: 'body', label: 'Body • JSON' },
            { id: 'cookies', label: `Cookies (${current.cookies.length})` },
            { id: 'raw', label: 'Raw HTTP' },
          ] as const
        ).map((tab) => {
          const active = subTab === tab.id;
          return (
            <button
              key={tab.id}
              type="button"
              onClick={() => setSubTab(tab.id)}
              className={`px-2.5 py-1 rounded-md font-mono text-[11px] font-semibold whitespace-nowrap shrink-0 cursor-pointer transition-colors ${
                active
                  ? 'bg-[#4cd7f6] text-[#003640]'
                  : 'bg-[#1c2025] text-[#bcc9cd] hover:text-[#e0e2ea]'
              }`}
            >
              {tab.label}
            </button>
          );
        })}
      </div>

      {/* Scrollable Inspection & Modification Viewport */}
      <div className="flex-1 overflow-y-auto min-h-0 px-3 space-y-3 pb-3">
        {(subTab === 'headers' || subTab === 'body') && (
          <>
            {subTab === 'headers' && (
              <div className="bg-[#181c21] rounded-xl p-2.5 space-y-1.5">
                <div className="flex items-center justify-between px-1 pb-1">
                  <span className="font-mono text-[11px] uppercase tracking-wider text-[#bcc9cd] font-semibold">
                    REQUEST HEADERS
                  </span>
                  <div className="flex items-center gap-2">
                    <span className="font-mono text-[11px] text-[#4cd7f6] tabular-nums">
                      {current.headers.length} Entries
                    </span>
                    <button
                      type="button"
                      onClick={() => setEditingHeaders((v) => !v)}
                      className="px-2 py-0.5 rounded bg-[#262a30] text-[#4cd7f6] font-mono text-[10px] cursor-pointer"
                    >
                      {editingHeaders ? 'Done' : 'Edit'}
                    </button>
                  </div>
                </div>

                {current.headers.map((hdr, idx) => {
                  const isAuth = hdr.name.toLowerCase() === 'authorization';
                  return (
                    <div
                      key={`${hdr.name}-${idx}`}
                      className="p-2 rounded-lg bg-[#1c2025] flex flex-col gap-0.5"
                    >
                      <div className="flex items-center justify-between">
                        <span
                          className={`font-mono text-[11px] font-semibold flex items-center gap-1 ${
                            isAuth ? 'text-[#ffb95f]' : 'text-[#4cd7f6]'
                          }`}
                        >
                          {isAuth && <Key className="w-3 h-3" />}
                          {hdr.name}
                        </span>
                        <span
                          className={`px-1.5 py-0.5 rounded font-mono text-[9px] uppercase ${
                            isAuth
                              ? 'bg-[#e79400]/30 text-[#ffb95f]'
                              : 'bg-[#31353b] text-[#bcc9cd]'
                          }`}
                        >
                          {hdr.category || 'Header'}
                        </span>
                      </div>
                      {editingHeaders ? (
                        <input
                          type="text"
                          value={hdr.value}
                          onChange={(e) => updateInterceptedHeader(current.id, idx, e.target.value)}
                          className="mt-1 bg-[#0a0e13] border border-[#3d494c] rounded px-2 py-1 font-mono text-[12px] text-[#e0e2ea] focus:outline-none focus:border-[#4cd7f6]"
                        />
                      ) : (
                        <div className="font-mono text-[12px] text-[#e0e2ea] break-all">
                          {hdr.value}
                        </div>
                      )}
                    </div>
                  );
                })}
              </div>
            )}

            {/* Intercepted Payload Editor Panel */}
            <div className="bg-[#181c21] rounded-xl p-2.5 space-y-2">
              <div className="flex items-center justify-between px-1">
                <div className="flex items-center gap-1.5">
                  <span className="w-2 h-2 rounded-full bg-[#4edea3]" />
                  <span className="font-mono text-[11px] uppercase tracking-wider text-[#e0e2ea] font-semibold">
                    INTERCEPTED PAYLOAD
                  </span>
                  <span className="text-[#bcc9cd] font-mono text-[11px]">
                    ({current.contentType})
                  </span>
                </div>
                <button
                  type="button"
                  onClick={() => setEditingPayload((v) => !v)}
                  className="px-2 py-1 rounded bg-[#262a30] text-[#4cd7f6] hover:bg-[#31353b] font-mono text-[11px] flex items-center gap-1 cursor-pointer"
                >
                  <Edit3 className="w-3 h-3" />
                  <span>{editingPayload ? 'Done' : 'Edit'}</span>
                </button>
              </div>

              <SyntaxCodeBlock
                code={current.body}
                mode="json"
                editable={editingPayload}
                onChange={(val) => updateInterceptedBody(current.id, val)}
              />

              <div className="flex items-center gap-1.5 px-1 text-[#bcc9cd] text-[11px]">
                <BadgeCheck className="w-3.5 h-3.5 text-[#4edea3] shrink-0" />
                <span>Payload editable: any modification will be dispatched upon forward.</span>
              </div>
            </div>
          </>
        )}

        {subTab === 'params' && (
          <div className="bg-[#181c21] rounded-xl p-6 text-center text-xs text-[#869397] font-mono">
            No query parameters on {current.path}
          </div>
        )}

        {subTab === 'cookies' && (
          <div className="bg-[#181c21] rounded-xl p-3 space-y-2">
            {current.cookies.map((c) => (
              <div key={c.name} className="p-2 rounded bg-[#0a0e13] flex flex-col gap-0.5">
                <span className="font-mono text-[11px] font-semibold text-[#4cd7f6]">{c.name}</span>
                <span className="font-mono text-[12px] text-[#4edea3] break-all">{c.value}</span>
              </div>
            ))}
          </div>
        )}

        {subTab === 'raw' && (
          <div className="bg-[#181c21] rounded-xl p-2.5">
            <SyntaxCodeBlock code={rawHttp} mode="http" />
          </div>
        )}
      </div>

      {/* Fixed Bottom Action Bar */}
      <div className="px-3 py-2.5 bg-[#0a0e13] border-t border-[#262a30] flex items-center gap-2 shrink-0">
        <button
          type="button"
          onClick={() => {
            setSubTab('body');
            setEditingPayload(true);
          }}
          aria-label="Modify Request"
          className="px-3 py-3 rounded-lg bg-[#1c2025] text-[#4cd7f6] hover:bg-[#262a30] active:scale-95 transition-transform flex items-center justify-center cursor-pointer"
        >
          <FilePenLine className="w-4 h-4" />
        </button>

        <button
          type="button"
          onClick={() => dropIntercepted(current.id)}
          className="px-4 py-3 rounded-lg bg-[#93000a] hover:bg-[#a80812] text-[#ffb4ab] text-[14px] font-semibold flex items-center justify-center gap-1.5 active:scale-95 transition-transform cursor-pointer"
        >
          <X className="w-4 h-4" />
          <span>DROP</span>
        </button>

        <button
          type="button"
          onClick={() => forwardIntercepted(current.id)}
          className="flex-1 py-3 px-4 rounded-lg bg-[#4cd7f6] hover:bg-[#3cc7e6] text-[#003640] text-[14px] font-bold flex items-center justify-center gap-2 shadow-md active:scale-[0.98] transition-transform cursor-pointer"
        >
          <ArrowRight className="w-4 h-4" />
          <span>FORWARD</span>
        </button>
      </div>
    </div>
  );
};
