import React, { useMemo, useState } from 'react';
import {
  ArrowDown,
  ArrowUp,
  ChevronDown,
  Code2,
  Copy,
  CopyPlus,
  History,
  Plus,
  RefreshCw,
  Server,
  Terminal,
  X,
  Zap,
} from 'lucide-react';
import { useProxy } from '../context/ProxyContext';
import { SyntaxCodeBlock } from '../components/SyntaxCodeBlock';
import { HttpMethod } from '../types/proxy';

type RepeaterDirection = 'request' | 'response';
type RequestSubTab = 'raw' | 'headers' | 'params' | 'body' | 'cookies';
type ResponseSubTab = 'json' | 'headers' | 'hex';

const METHODS: HttpMethod[] = ['GET', 'POST', 'PUT', 'DELETE', 'PATCH'];

export const RepeaterView: React.FC = () => {
  const {
    repeaterTabs,
    activeRepeaterTab,
    setActiveRepeaterTabId,
    updateRepeaterTab,
    dispatchRepeaterRequest,
    duplicateRepeaterTab,
    closeRepeaterTab,
    copyText,
    showToast,
  } = useProxy();

  const [direction, setDirection] = useState<RepeaterDirection>('request');
  const [reqSubTab, setReqSubTab] = useState<RequestSubTab>('raw');
  const [resSubTab, setResSubTab] = useState<ResponseSubTab>('json');
  const [editingRaw, setEditingRaw] = useState(false);
  const [dispatching, setDispatching] = useState(false);
  const [showMethodMenu, setShowMethodMenu] = useState(false);
  const [showHistoryMenu, setShowHistoryMenu] = useState(false);

  const tab = activeRepeaterTab;

  const rawPayload = useMemo(() => {
    const hdrs = tab.headers.map((h) => `${h.name}: ${h.value}`).join('\n');
    return `${tab.method} ${tab.path} HTTP/1.1\n${hdrs}\n\n${tab.body}`;
  }, [tab]);

  const handleSend = () => {
    setDispatching(true);
    dispatchRepeaterRequest(tab.id);
    window.setTimeout(() => {
      setDispatching(false);
      setDirection('response');
    }, 280);
  };

  const handleFormatJson = () => {
    try {
      const formatted = JSON.stringify(JSON.parse(tab.body), null, 2);
      updateRepeaterTab(tab.id, { body: formatted });
      showToast('Formatted JSON payload');
    } catch {
      showToast('Payload is not valid JSON');
    }
  };

  const buildCurl = () => {
    const hdrFlags = tab.headers
      .filter((h) => h.name.toLowerCase() !== 'host')
      .map((h) => `-H "${h.name}: ${h.value}"`)
      .join(' \\\n  ');
    const dataFlag = tab.body ? ` \\\n  -d '${tab.body.replace(/\n/g, ' ')}'` : '';
    return `curl -X ${tab.method} "${tab.url}" \\\n  ${hdrFlags}${dataFlag}`;
  };

  return (
    <div className="flex flex-col flex-1 min-h-0 w-full bg-[#101419] text-[#e0e2ea]">
      {/* Multi-Tab Strip when > 1 tab or for quick management */}
      <div className="px-3 pt-2 bg-[#0a0e13] flex items-center gap-1.5 overflow-x-auto no-scrollbar border-b border-[#262a30]/50 shrink-0">
        {repeaterTabs.map((t) => {
          const isActive = t.id === tab.id;
          return (
            <div
              key={t.id}
              className={`flex items-center gap-1.5 px-2.5 py-1.5 rounded-t-md font-mono text-[11px] shrink-0 transition-colors ${
                isActive
                  ? 'bg-[#181c21] text-[#4cd7f6] font-semibold border-t border-x border-[#262a30]'
                  : 'text-[#869397] hover:text-[#e0e2ea]'
              }`}
            >
              <button
                type="button"
                onClick={() => setActiveRepeaterTabId(t.id)}
                className="cursor-pointer whitespace-nowrap"
              >
                {t.label}
              </button>
              {repeaterTabs.length > 1 && (
                <button
                  type="button"
                  onClick={() => closeRepeaterTab(t.id)}
                  aria-label={`Close ${t.label}`}
                  className="text-[#869397] hover:text-[#ffb4ab] cursor-pointer"
                >
                  <X className="w-3 h-3" />
                </button>
              )}
            </div>
          );
        })}
        <button
          type="button"
          onClick={() => duplicateRepeaterTab(tab.id)}
          aria-label="New Repeater Tab"
          className="p-1.5 text-[#869397] hover:text-[#4cd7f6] cursor-pointer shrink-0"
        >
          <Plus className="w-3.5 h-3.5" />
        </button>
      </div>

      {/* Transaction Metadata Strip */}
      <div className="px-3 py-2 bg-[#0a0e13] flex flex-col gap-1 shrink-0">
        <div className="flex items-center justify-between gap-2">
          <div className="flex items-center gap-1.5 min-w-0">
            <span className="px-1.5 py-0.5 rounded bg-[#06b6d4]/20 text-[#4cd7f6] font-mono text-[10px] uppercase font-semibold">
              {tab.label}
            </span>
            <span className="font-mono text-[12px] font-semibold text-[#e0e2ea] truncate tabular-nums">
              {tab.sourceSequence
                ? `Request #${String(tab.sourceSequence).padStart(3, '0')}`
                : 'Custom Request'}
            </span>
            <span className="px-1.5 py-0.5 rounded bg-[#262a30] text-[#bcc9cd] font-mono text-[10px]">
              HTTPS
            </span>
          </div>
          <div className="flex items-center gap-1.5 text-[#bcc9cd] font-mono text-[11px] tabular-nums shrink-0">
            <span
              className={`font-semibold ${
                tab.lastStatus >= 400 ? 'text-[#ffb4ab]' : 'text-[#4edea3]'
              }`}
            >
              {tab.lastStatus} {tab.lastStatusText}
            </span>
            <span className="text-[#869397]">·</span>
            <span>{tab.lastDurationMs}ms</span>
            <span className="text-[#869397]">·</span>
            <span>{tab.lastSizeLabel}</span>
          </div>
        </div>
        <div className="flex items-center justify-between text-[#bcc9cd] font-mono text-[11px]">
          <div className="flex items-center gap-1.5 truncate">
            <Server className="w-3.5 h-3.5 text-[#869397] shrink-0" />
            <span className="truncate text-[#e0e2ea]">{tab.host}</span>
          </div>
          <span className="text-[#869397] shrink-0 tabular-nums">
            Sent: {String(tab.sentCount).padStart(2, '0')} times
          </span>
        </div>
      </div>

      {/* Method & URL Editor Bar */}
      <div className="px-3 py-2 bg-[#181c21] flex items-center gap-1.5 relative shrink-0">
        <div className="relative">
          <button
            type="button"
            onClick={() => setShowMethodMenu((v) => !v)}
            className="flex items-center gap-1 bg-[#e79400]/20 text-[#ffb95f] px-2.5 py-1.5 rounded font-mono text-[11px] font-bold tracking-wider active:scale-95 transition-transform shrink-0 cursor-pointer"
          >
            <span>{tab.method}</span>
            <ChevronDown className="w-3.5 h-3.5" />
          </button>

          {showMethodMenu && (
            <div className="absolute left-0 top-full mt-1 z-30 w-28 rounded-lg bg-[#1c2025] border border-[#3d494c] shadow-xl py-1">
              {METHODS.map((m) => (
                <button
                  key={m}
                  type="button"
                  onClick={() => {
                    updateRepeaterTab(tab.id, { method: m });
                    setShowMethodMenu(false);
                  }}
                  className={`w-full text-left px-3 py-1.5 font-mono text-xs hover:bg-[#262a30] cursor-pointer ${
                    m === tab.method ? 'text-[#4cd7f6] font-bold' : 'text-[#e0e2ea]'
                  }`}
                >
                  {m}
                </button>
              ))}
            </div>
          )}
        </div>

        <div className="flex-1 min-w-0 bg-[#1c2025] px-2.5 py-1 rounded flex items-center justify-between gap-1.5">
          <input
            type="text"
            value={tab.url}
            onChange={(e) => updateRepeaterTab(tab.id, { url: e.target.value })}
            aria-label="Target URL"
            className="flex-1 min-w-0 bg-transparent font-mono text-[12px] text-[#e0e2ea] focus:outline-none"
          />
          <div className="flex items-center gap-1 shrink-0">
            <button
              type="button"
              onClick={() => copyText(tab.url, 'Copied URL')}
              aria-label="Copy URL"
              className="w-6 h-6 rounded flex items-center justify-center text-[#869397] hover:text-[#e0e2ea] cursor-pointer"
            >
              <Copy className="w-3.5 h-3.5" />
            </button>
            <button
              type="button"
              onClick={() => setShowHistoryMenu((v) => !v)}
              aria-label="Execution History"
              className="w-6 h-6 rounded flex items-center justify-center text-[#869397] hover:text-[#e0e2ea] cursor-pointer"
            >
              <History className="w-3.5 h-3.5" />
            </button>
          </div>
        </div>
      </div>

      {/* Primary Request / Response Segmented Bar (hidden on xl split view? Keep visible on mobile & lg, and show split on xl!) */}
      <div className="px-3 pt-1.5 pb-1 bg-[#181c21] xl:hidden shrink-0">
        <div className="grid grid-cols-2 p-1 rounded-lg bg-[#0a0e13] gap-1">
          <button
            type="button"
            onClick={() => setDirection('request')}
            className={`flex items-center justify-center gap-1.5 py-2 px-3 rounded text-[13px] font-semibold transition-all cursor-pointer ${
              direction === 'request'
                ? 'bg-[#06b6d4] text-[#003640] shadow-sm'
                : 'text-[#bcc9cd] hover:text-[#e0e2ea]'
            }`}
          >
            <ArrowUp className="w-4 h-4" />
            <span>REQUEST</span>
          </button>
          <button
            type="button"
            onClick={() => setDirection('response')}
            className={`flex items-center justify-center gap-1.5 py-2 px-3 rounded text-[13px] font-semibold transition-all cursor-pointer ${
              direction === 'response'
                ? 'bg-[#06b6d4] text-[#003640] shadow-sm'
                : 'text-[#bcc9cd] hover:text-[#e0e2ea]'
            }`}
          >
            <ArrowDown
              className={`w-4 h-4 ${direction === 'response' ? 'text-[#003640]' : 'text-[#4edea3]'}`}
            />
            <span>RESPONSE</span>
            <span
              className={`px-1.5 py-0.5 rounded font-mono text-[10px] tabular-nums ${
                direction === 'response'
                  ? 'bg-[#003640]/20 text-[#003640]'
                  : 'bg-[#4edea3]/15 text-[#4edea3]'
              }`}
            >
              {tab.lastStatus}
            </span>
          </button>
        </div>
      </div>

      {/* Main Content Area (Tabbed on <xl, Side-by-Side Request/Response on xl+) */}
      <div className="flex-1 min-h-0 flex flex-col xl:flex-row overflow-hidden">
        {/* REQUEST PANE */}
        <div
          className={`flex-1 flex flex-col min-h-0 xl:border-r xl:border-[#262a30] ${
            direction === 'request' ? 'flex' : 'hidden xl:flex'
          }`}
        >
          {/* Sub-Tabs */}
          <div className="px-3 py-2 bg-[#181c21] flex items-center gap-1 overflow-x-auto no-scrollbar shrink-0">
            {(
              [
                { id: 'raw', label: 'Raw' },
                { id: 'headers', label: `Headers (${tab.headers.length})` },
                { id: 'params', label: `Params (${tab.params.length})` },
                { id: 'body', label: 'Body (JSON)' },
                { id: 'cookies', label: `Cookies (${tab.cookies.length})` },
              ] as const
            ).map((sub) => (
              <button
                key={sub.id}
                type="button"
                onClick={() => setReqSubTab(sub.id)}
                className={`px-2.5 py-1 rounded font-mono text-[11px] whitespace-nowrap shrink-0 cursor-pointer transition-colors ${
                  reqSubTab === sub.id
                    ? 'bg-[#31353b] text-[#e0e2ea] font-semibold'
                    : 'bg-[#1c2025] text-[#bcc9cd] hover:text-[#e0e2ea]'
                }`}
              >
                {sub.label}
              </button>
            ))}
          </div>

          {/* Editor Header Tools */}
          <div className="px-3 py-1.5 bg-[#1c2025] flex items-center justify-between text-[#869397] shrink-0">
            <div className="flex items-center gap-2 font-mono text-[11px] tabular-nums">
              <span className="flex items-center gap-1.5 text-[#bcc9cd]">
                <span className="w-1.5 h-1.5 rounded-full bg-[#4cd7f6] animate-pulse" />
                Ln {(tab.body || '').split('\n').length}, Col 1
              </span>
              <span>·</span>
              <span>{tab.body.length} bytes</span>
            </div>
            <div className="flex items-center gap-1">
              <button
                type="button"
                onClick={() => setEditingRaw((v) => !v)}
                className={`px-2 py-0.5 rounded font-mono text-[10px] cursor-pointer ${
                  editingRaw
                    ? 'bg-[#4cd7f6]/20 text-[#4cd7f6]'
                    : 'bg-[#262a30] text-[#bcc9cd] hover:text-[#e0e2ea]'
                }`}
              >
                {editingRaw ? 'Preview' : 'Edit Body'}
              </button>
              <button
                type="button"
                onClick={handleFormatJson}
                aria-label="Format JSON"
                title="Format JSON"
                className="p-1 rounded text-[#bcc9cd] hover:text-[#4cd7f6] hover:bg-[#262a30] cursor-pointer"
              >
                <Code2 className="w-4 h-4" />
              </button>
              <button
                type="button"
                onClick={() => copyText(rawPayload, 'Copied raw payload')}
                aria-label="Copy raw payload"
                title="Copy raw payload"
                className="p-1 rounded text-[#bcc9cd] hover:text-[#4cd7f6] hover:bg-[#262a30] cursor-pointer"
              >
                <Copy className="w-4 h-4" />
              </button>
              <button
                type="button"
                onClick={() => duplicateRepeaterTab(tab.id)}
                aria-label="Duplicate Tab"
                title="Duplicate Tab"
                className="p-1 rounded text-[#bcc9cd] hover:text-[#4cd7f6] hover:bg-[#262a30] cursor-pointer"
              >
                <CopyPlus className="w-4 h-4" />
              </button>
            </div>
          </div>

          {/* Code / Sub-tab Content */}
          <div className="flex-1 overflow-y-auto bg-[#0a0e13] p-3 min-h-0">
            {reqSubTab === 'raw' && (
              <SyntaxCodeBlock
                code={editingRaw ? tab.body : rawPayload}
                mode={editingRaw ? 'json' : 'http'}
                editable={editingRaw}
                onChange={(val) => updateRepeaterTab(tab.id, { body: val })}
              />
            )}

            {reqSubTab === 'headers' && (
              <div className="space-y-2">
                {tab.headers.map((h, i) => (
                  <div key={`${h.name}-${i}`} className="p-2.5 rounded-lg bg-[#181c21] space-y-1">
                    <span className="font-mono text-[11px] font-semibold text-[#4cd7f6]">
                      {h.name}
                    </span>
                    <input
                      type="text"
                      value={h.value}
                      onChange={(e) => {
                        const updated = tab.headers.map((item, idx) =>
                          idx === i ? { ...item, value: e.target.value } : item
                        );
                        updateRepeaterTab(tab.id, { headers: updated });
                      }}
                      className="w-full bg-[#0a0e13] border border-[#262a30] rounded px-2 py-1 font-mono text-[12px] text-[#e0e2ea] focus:outline-none focus:border-[#4cd7f6]"
                    />
                  </div>
                ))}
              </div>
            )}

            {reqSubTab === 'body' && (
              <SyntaxCodeBlock
                code={tab.body}
                mode="json"
                editable
                onChange={(val) => updateRepeaterTab(tab.id, { body: val })}
              />
            )}

            {reqSubTab === 'params' && (
              <div className="py-8 text-center text-xs text-[#869397] font-mono">
                {tab.params.length === 0
                  ? 'No query parameters on this request'
                  : tab.params.map((p) => `${p.key}=${p.value}`).join('&')}
              </div>
            )}

            {reqSubTab === 'cookies' && (
              <div className="space-y-2">
                {tab.cookies.map((c) => (
                  <div key={c.name} className="p-2.5 rounded-lg bg-[#181c21]">
                    <div className="font-mono text-[11px] text-[#4cd7f6] font-semibold">
                      {c.name}
                    </div>
                    <div className="font-mono text-[12px] text-[#4edea3] break-all mt-0.5">
                      {c.value}
                    </div>
                  </div>
                ))}
              </div>
            )}
          </div>
        </div>

        {/* RESPONSE PANE */}
        <div
          className={`flex-1 flex flex-col min-h-0 ${
            direction === 'response' ? 'flex' : 'hidden xl:flex'
          }`}
        >
          <div className="px-3 py-2 bg-[#1c2025] flex items-center justify-between shrink-0">
            <div className="flex items-center gap-2 font-mono text-[11px] tabular-nums">
              <span
                className={`px-1.5 py-0.5 rounded font-bold ${
                  tab.lastStatus >= 400
                    ? 'bg-[#93000a]/30 text-[#ffb4ab]'
                    : 'bg-[#4edea3]/15 text-[#4edea3]'
                }`}
              >
                {tab.lastStatus} {tab.lastStatusText}
              </span>
              <span className="text-[#bcc9cd]">{tab.lastDurationMs} ms</span>
              <span className="text-[#869397]">·</span>
              <span className="text-[#bcc9cd]">{tab.lastSizeBytes} bytes</span>
            </div>
            <span className="font-mono text-[11px] text-[#869397]">server: nginx/1.24</span>
          </div>

          <div className="px-3 py-2 bg-[#181c21] flex items-center gap-1 overflow-x-auto no-scrollbar shrink-0">
            {(
              [
                { id: 'json', label: 'Rendered JSON' },
                { id: 'headers', label: `Headers (${tab.responseHeaders.length})` },
                { id: 'hex', label: 'Raw Hex' },
              ] as const
            ).map((sub) => (
              <button
                key={sub.id}
                type="button"
                onClick={() => setResSubTab(sub.id)}
                className={`px-2.5 py-1 rounded font-mono text-[11px] whitespace-nowrap shrink-0 cursor-pointer transition-colors ${
                  resSubTab === sub.id
                    ? 'bg-[#31353b] text-[#e0e2ea] font-semibold'
                    : 'bg-[#1c2025] text-[#bcc9cd] hover:text-[#e0e2ea]'
                }`}
              >
                {sub.label}
              </button>
            ))}
          </div>

          <div className="flex-1 overflow-y-auto bg-[#0a0e13] p-3 min-h-0">
            {resSubTab === 'json' && <SyntaxCodeBlock code={tab.responseBody} mode="json" />}
            {resSubTab === 'headers' && (
              <div className="space-y-1.5">
                {tab.responseHeaders.map((h, i) => (
                  <div key={`${h.name}-${i}`} className="p-2 rounded bg-[#181c21] flex flex-col gap-0.5">
                    <span className="font-mono text-[11px] font-semibold text-[#4cd7f6]">
                      {h.name}
                    </span>
                    <span className="font-mono text-[12px] text-[#e0e2ea] break-all">{h.value}</span>
                  </div>
                ))}
              </div>
            )}
            {resSubTab === 'hex' && <SyntaxCodeBlock code={tab.responseBody} mode="hex" />}
          </div>
        </div>
      </div>

      {/* Sticky Tactical Execution Strip */}
      <div className="px-3 py-2.5 bg-[#181c21] border-t border-[#262a30] flex flex-col gap-2 shrink-0 relative">
        {showHistoryMenu && (
          <div className="absolute bottom-full right-3 mb-2 w-72 rounded-xl bg-[#1c2025] border border-[#3d494c] shadow-2xl p-2 z-30">
            <div className="flex items-center justify-between px-2 py-1 border-b border-[#262a30] mb-1">
              <span className="font-mono text-[10px] uppercase tracking-wider text-[#4cd7f6] font-semibold">
                Replay History ({tab.history.length})
              </span>
              <button
                type="button"
                onClick={() => setShowHistoryMenu(false)}
                className="text-[#869397] hover:text-[#e0e2ea] cursor-pointer"
              >
                <X className="w-3.5 h-3.5" />
              </button>
            </div>
            <div className="max-h-48 overflow-y-auto space-y-1">
              {tab.history.map((h) => (
                <div
                  key={h.id}
                  className="px-2 py-1.5 rounded bg-[#101419] flex items-center justify-between font-mono text-[11px] tabular-nums"
                >
                  <span className="text-[#e0e2ea]">{h.timestamp}</span>
                  <span
                    className={h.status >= 400 ? 'text-[#ffb4ab]' : 'text-[#4edea3]'}
                  >
                    {h.status} {h.statusText}
                  </span>
                  <span className="text-[#869397]">{h.durationMs}ms</span>
                </div>
              ))}
            </div>
          </div>
        )}

        <div className="flex items-center gap-2">
          <button
            type="button"
            onClick={handleSend}
            disabled={dispatching}
            className="flex-1 py-2.5 px-4 rounded-lg bg-[#4cd7f6] hover:bg-[#3cc7e6] text-[#003640] text-[14px] font-bold flex items-center justify-center gap-2 active:scale-[0.98] transition-all shadow-lg shadow-[#06b6d4]/20 cursor-pointer"
          >
            {dispatching ? (
              <RefreshCw className="w-4 h-4 animate-spin" />
            ) : (
              <Zap className="w-4 h-4" />
            )}
            <span className="tracking-wide">
              {dispatching ? 'DISPATCHING...' : 'SEND REQUEST'}
            </span>
          </button>

          <button
            type="button"
            onClick={() => setShowHistoryMenu((v) => !v)}
            className="h-10 px-3 rounded-lg bg-[#262a30] hover:bg-[#31353b] text-[#e0e2ea] flex items-center gap-1.5 transition-colors shrink-0 cursor-pointer tabular-nums"
          >
            <History className="w-4 h-4 text-[#4cd7f6]" />
            <span className="font-mono text-[11px] font-semibold">
              {String(tab.sentCount).padStart(2, '0')}
            </span>
            <ChevronDown className="w-3.5 h-3.5 text-[#869397]" />
          </button>

          <button
            type="button"
            onClick={() => copyText(buildCurl(), 'Copied cURL command')}
            title="Export to cURL"
            aria-label="Export to cURL"
            className="w-10 h-10 rounded-lg bg-[#262a30] hover:bg-[#31353b] text-[#e0e2ea] flex items-center justify-center transition-colors shrink-0 cursor-pointer"
          >
            <Terminal className="w-4 h-4" />
          </button>
        </div>

        <div className="flex items-center justify-between px-0.5 text-[#869397] font-mono text-[11px]">
          <span className="flex items-center gap-1.5">
            <span className="w-1.5 h-1.5 rounded-full bg-[#4edea3]" />
            Socket Connected (TLS 1.3)
          </span>
          <span>TLS ALPN: h1</span>
        </div>
      </div>
    </div>
  );
};
