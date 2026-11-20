import React, { useMemo, useState } from 'react';
import {
  ArrowDown,
  ArrowLeft,
  ArrowUp,
  Check,
  Copy,
  Edit3,
  Key,
  Lock,
  Repeat,
  Terminal,
  Timer,
  Trash2,
} from 'lucide-react';
import { useProxy } from '../context/ProxyContext';
import { SyntaxCodeBlock } from '../components/SyntaxCodeBlock';
import { HttpTransaction } from '../types/proxy';

interface RequestDetailViewProps {
  transaction?: HttpTransaction | null;
  embedded?: boolean;
}

type DirectionTab = 'request' | 'response';
type DetailSubTab = 'headers' | 'params' | 'body' | 'cookies' | 'raw';

export const RequestDetailView: React.FC<RequestDetailViewProps> = ({
  transaction: propTransaction,
  embedded = false,
}) => {
  const {
    selectedTransaction,
    goBack,
    sendToRepeater,
    deleteTransaction,
    copyText,
    settings,
    showToast,
  } = useProxy();

  const tx = propTransaction ?? selectedTransaction;
  const [direction, setDirection] = useState<DirectionTab>('request');
  const [subTab, setSubTab] = useState<DetailSubTab>('headers');
  const [isEditingBody, setIsEditingBody] = useState(false);
  const [editedBody, setEditedBody] = useState<string | null>(null);
  const [copiedId, setCopiedId] = useState<string | null>(null);

  const activeBody = useMemo(() => {
    if (!tx) return '';
    if (direction === 'request') {
      return editedBody ?? tx.requestBody;
    }
    return tx.responseBody;
  }, [direction, editedBody, tx]);

  if (!tx) {
    return (
      <div className="flex-1 flex flex-col items-center justify-center p-8 text-center text-[#869397]">
        <p className="text-sm font-medium text-[#e0e2ea]">No request selected</p>
        <p className="text-xs mt-1">Select a captured HTTP transaction from the stream to inspect headers and payloads.</p>
      </div>
    );
  }

  const headers = direction === 'request' ? tx.requestHeaders : tx.responseHeaders;
  const params = tx.requestParams;
  const cookies = tx.requestCookies;

  const methodBadgeColor =
    tx.method === 'POST'
      ? 'bg-[#e79400]/20 text-[#ffb95f]'
      : tx.method === 'PUT'
        ? 'bg-[#00a572]/20 text-[#4edea3]'
        : tx.method === 'DELETE'
          ? 'bg-[#93000a]/40 text-[#ffb4ab]'
          : 'bg-[#06b6d4]/20 text-[#4cd7f6]';

  const statusBadgeColor =
    tx.status >= 400
      ? 'bg-[#93000a]/40 text-[#ffb4ab]'
      : tx.status >= 300
        ? 'bg-[#262a30] text-[#bcc9cd]'
        : 'bg-[#00a572]/20 text-[#4edea3]';

  const statusDotColor =
    tx.status >= 400 ? 'bg-[#ffb4ab]' : tx.status >= 300 ? 'bg-[#bcc9cd]' : 'bg-[#4edea3]';

  const handleCopy = (key: string, text: string, label: string) => {
    copyText(text, label);
    setCopiedId(key);
    window.setTimeout(() => setCopiedId(null), 1400);
  };

  const buildCurl = () => {
    const hdrFlags = tx.requestHeaders
      .filter((h) => h.name.toLowerCase() !== 'host')
      .map((h) => `-H "${h.name}: ${h.value}"`)
      .join(' \\\n  ');
    const dataFlag = tx.requestBody ? ` \\\n  -d '${tx.requestBody.replace(/\n/g, ' ')}'` : '';
    return `curl -X ${tx.method} "https://${tx.host}${tx.path}" \\\n  ${hdrFlags}${dataFlag}`;
  };

  const rawHttpText = useMemo(() => {
    if (direction === 'request') {
      const hdrLines = tx.requestHeaders.map((h) => `${h.name}: ${h.value}`).join('\n');
      return `${tx.method} ${tx.path} HTTP/1.1\n${hdrLines}\n\n${activeBody || ''}`;
    }
    const hdrLines = tx.responseHeaders.map((h) => `${h.name}: ${h.value}`).join('\n');
    return `HTTP/1.1 ${tx.status} ${tx.statusText}\n${hdrLines}\n\n${tx.responseBody || ''}`;
  }, [activeBody, direction, tx]);

  return (
    <div className="flex flex-col w-full h-full min-h-0 bg-[#101419] text-[#e0e2ea]">
      {/* Sub-header Bar */}
      <div className="flex items-center justify-between px-3 py-2 bg-[#0a0e13] border-b border-[#262a30] shrink-0">
        <div className="flex items-center gap-2 min-w-0">
          {!embedded && (
            <button
              type="button"
              onClick={goBack}
              aria-label="Back to traffic list"
              className="w-8 h-8 rounded-lg bg-[#1c2025] hover:bg-[#262a30] flex items-center justify-center text-[#e0e2ea] active:scale-95 transition-all cursor-pointer shrink-0"
            >
              <ArrowLeft className="w-4 h-4" />
            </button>
          )}
          <div className="flex items-center gap-1.5 min-w-0">
            <span className="text-[15px] font-semibold text-[#e0e2ea] truncate tabular-nums">
              Request #{String(tx.sequenceNumber).padStart(3, '0')}
            </span>
            <span className="px-1.5 py-0.5 rounded bg-[#262a30] font-mono text-[10px] text-[#4cd7f6] font-semibold">
              FLOW
            </span>
          </div>
        </div>
        <div className="flex items-center gap-1.5 px-2.5 py-1 rounded-full bg-[#181c21] shrink-0">
          <span
            className={`w-1.5 h-1.5 rounded-full ${
              settings.proxyEnabled ? 'bg-[#4edea3] animate-pulse' : 'bg-[#ffb4ab]'
            }`}
          />
          <span className="font-mono text-[10px] text-[#4edea3] font-semibold tracking-wider uppercase">
            PROXY :{settings.port}
          </span>
        </div>
      </div>

      {/* Compact Summary Card */}
      <div className="px-3 py-2.5 bg-[#181c21] border-b border-[#262a30]/70 shrink-0">
        <div className="flex items-center justify-between gap-2">
          <div className="flex items-center gap-2 min-w-0 flex-1">
            <span
              className={`px-2 py-0.5 rounded font-mono text-[10px] font-bold tracking-wider shrink-0 ${methodBadgeColor}`}
            >
              {tx.method}
            </span>
            <span className="font-mono text-[13px] font-semibold text-[#e0e2ea] truncate">
              {tx.path}
            </span>
          </div>
          <div
            className={`flex items-center gap-1.5 px-2 py-0.5 rounded font-mono text-[11px] font-semibold shrink-0 tabular-nums ${statusBadgeColor}`}
          >
            <span className={`w-1.5 h-1.5 rounded-full ${statusDotColor}`} />
            <span>
              {tx.status} {tx.statusText}
            </span>
          </div>
        </div>

        <div className="grid grid-cols-4 gap-1.5 mt-2 bg-[#1c2025]/70 rounded-md p-1.5 text-center">
          <div className="flex flex-col">
            <span className="font-mono text-[9px] text-[#869397] uppercase">TIME</span>
            <span className="font-mono text-[11px] text-[#e0e2ea] font-medium flex items-center justify-center gap-0.5 tabular-nums">
              <Timer className="w-3 h-3 text-[#ffb95f]" />
              {tx.durationMs}ms
            </span>
          </div>
          <div className="flex flex-col">
            <span className="font-mono text-[9px] text-[#869397] uppercase">SIZE</span>
            <span className="font-mono text-[11px] text-[#e0e2ea] font-medium tabular-nums">
              {tx.sizeLabel}
            </span>
          </div>
          <div className="flex flex-col">
            <span className="font-mono text-[9px] text-[#869397] uppercase">PROTO</span>
            <span className="font-mono text-[11px] text-[#4cd7f6] font-medium">{tx.protocol}</span>
          </div>
          <div className="flex flex-col">
            <span className="font-mono text-[9px] text-[#869397] uppercase">CLIENT</span>
            <span className="font-mono text-[11px] text-[#e0e2ea] font-medium truncate">
              {tx.client}
            </span>
          </div>
        </div>
      </div>

      {/* Primary Request / Response Segmented Bar */}
      <div className="px-3 pt-2 shrink-0">
        <div className="grid grid-cols-2 p-1 bg-[#0a0e13] rounded-lg gap-1">
          <button
            type="button"
            onClick={() => {
              setDirection('request');
              setIsEditingBody(false);
            }}
            className={`flex items-center justify-center gap-1.5 py-2 rounded-md text-[12px] font-semibold transition-all cursor-pointer ${
              direction === 'request'
                ? 'bg-[#262a30] text-[#4cd7f6] shadow-sm'
                : 'text-[#bcc9cd] hover:text-[#e0e2ea]'
            }`}
          >
            <ArrowUp className="w-3.5 h-3.5" />
            <span>REQUEST</span>
          </button>
          <button
            type="button"
            onClick={() => {
              setDirection('response');
              setIsEditingBody(false);
            }}
            className={`flex items-center justify-center gap-1.5 py-2 rounded-md text-[12px] font-semibold transition-all cursor-pointer ${
              direction === 'response'
                ? 'bg-[#262a30] text-[#4cd7f6] shadow-sm'
                : 'text-[#bcc9cd] hover:text-[#e0e2ea]'
            }`}
          >
            <ArrowDown className="w-3.5 h-3.5" />
            <span>RESPONSE</span>
          </button>
        </div>
      </div>

      {/* Sub-Tabs Shelf */}
      <div className="px-3 py-2 shrink-0">
        <div className="flex items-center gap-1.5 overflow-x-auto no-scrollbar">
          {(
            [
              { id: 'headers', label: `Headers (${headers.length})` },
              { id: 'params', label: `Params (${params.length})` },
              {
                id: 'body',
                label: `Body (${
                  (direction === 'request' ? tx.requestContentType : tx.responseContentType)?.includes(
                    'json'
                  )
                    ? 'JSON'
                    : 'Text'
                })`,
              },
              { id: 'cookies', label: `Cookies (${cookies.length})` },
              { id: 'raw', label: 'Raw Hex' },
            ] as const
          ).map((tab) => {
            const active = subTab === tab.id;
            return (
              <button
                key={tab.id}
                type="button"
                onClick={() => setSubTab(tab.id)}
                className={`px-3 py-1.5 rounded font-mono text-[11px] font-semibold whitespace-nowrap shrink-0 transition-colors cursor-pointer ${
                  active
                    ? 'bg-[#06b6d4] text-[#003640]'
                    : 'bg-[#1c2025] text-[#bcc9cd] hover:bg-[#262a30] hover:text-[#e0e2ea]'
                }`}
              >
                {tab.label}
              </button>
            );
          })}
        </div>
      </div>

      {/* Scrollable Inspector Viewport */}
      <div className="flex-1 overflow-y-auto px-3 pb-4 space-y-3 min-h-0">
        {subTab === 'headers' && (
          <div className="bg-[#181c21] rounded-lg p-3 space-y-2">
            <div className="flex items-center justify-between pb-1.5 border-b border-[#262a30]">
              <div className="flex items-center gap-1.5">
                <span className="font-mono text-[11px] text-[#4cd7f6] tracking-wider uppercase font-semibold">
                  {direction === 'request' ? 'REQUEST HEADERS' : 'RESPONSE HEADERS'}
                </span>
                <span className="px-1.5 py-0.5 rounded bg-[#31353b] font-mono text-[10px] text-[#bcc9cd] tabular-nums">
                  {headers.length}
                </span>
              </div>
              <button
                type="button"
                onClick={() =>
                  handleCopy(
                    'hdrs',
                    headers.map((h) => `${h.name}: ${h.value}`).join('\n'),
                    'Copied headers'
                  )
                }
                className="px-2 py-1 rounded bg-[#1c2025] hover:bg-[#262a30] text-[#e0e2ea] font-mono text-[11px] flex items-center gap-1 cursor-pointer"
              >
                {copiedId === 'hdrs' ? (
                  <Check className="w-3 h-3 text-[#4edea3]" />
                ) : (
                  <Copy className="w-3 h-3" />
                )}
                <span>{copiedId === 'hdrs' ? 'Copied' : 'Copy'}</span>
              </button>
            </div>

            <div className="flex flex-col gap-1.5">
              {headers.map((hdr, idx) => {
                const isAuth = hdr.name.toLowerCase() === 'authorization';
                return (
                  <div
                    key={`${hdr.name}-${idx}`}
                    className="p-2 rounded bg-[#0a0e13] flex flex-col gap-0.5"
                  >
                    <div className="flex items-center justify-between gap-2">
                      <span
                        className={`font-mono text-[11px] font-semibold ${
                          isAuth ? 'text-[#ffb95f]' : 'text-[#4cd7f6]'
                        }`}
                      >
                        {hdr.name}
                      </span>
                      <span
                        className={`font-mono text-[10px] flex items-center gap-1 ${
                          isAuth ? 'text-[#ffb95f]' : 'text-[#869397]'
                        }`}
                      >
                        {isAuth && <Key className="w-3 h-3" />}
                        {hdr.category || 'header'}
                      </span>
                    </div>
                    <span className="font-mono text-[12px] text-[#e0e2ea] break-all select-all">
                      {hdr.value}
                    </span>
                  </div>
                );
              })}
            </div>
          </div>
        )}

        {subTab === 'params' && (
          <div className="bg-[#181c21] rounded-lg p-3 space-y-2">
            <div className="flex items-center justify-between pb-1.5 border-b border-[#262a30]">
              <span className="font-mono text-[11px] text-[#4cd7f6] tracking-wider uppercase font-semibold">
                QUERY PARAMETERS
              </span>
              <span className="font-mono text-[10px] text-[#869397] tabular-nums">
                {params.length} entries
              </span>
            </div>
            {params.length === 0 ? (
              <div className="py-6 text-center text-xs text-[#869397]">
                No query parameters present on {tx.path}
              </div>
            ) : (
              <div className="flex flex-col gap-1.5">
                {params.map((p) => (
                  <div key={p.key} className="p-2 rounded bg-[#0a0e13] flex items-center justify-between">
                    <span className="font-mono text-[12px] text-[#4cd7f6] font-semibold">{p.key}</span>
                    <span className="font-mono text-[12px] text-[#4edea3]">{p.value}</span>
                  </div>
                ))}
              </div>
            )}
          </div>
        )}

        {subTab === 'body' && (
          <div className="bg-[#181c21] rounded-lg p-3 space-y-2.5">
            <div className="flex items-center justify-between">
              <div className="flex items-center gap-2">
                <span className="font-mono text-[11px] text-[#4cd7f6] tracking-wider uppercase font-semibold">
                  {direction === 'request' ? 'REQUEST BODY' : 'RESPONSE BODY'}
                </span>
                <span className="px-1.5 py-0.5 rounded bg-[#e79400]/20 text-[#ffb95f] font-mono text-[10px]">
                  {(direction === 'request' ? tx.requestContentType : tx.responseContentType) ||
                    'application/json'}
                </span>
              </div>
              <div className="flex items-center gap-1.5">
                {direction === 'request' && (
                  <button
                    type="button"
                    onClick={() => {
                      if (isEditingBody) {
                        setIsEditingBody(false);
                        showToast('Payload changes staged');
                      } else {
                        setIsEditingBody(true);
                      }
                    }}
                    className="px-2 py-1 rounded bg-[#1c2025] hover:bg-[#262a30] text-[#e0e2ea] font-mono text-[11px] flex items-center gap-1 cursor-pointer"
                  >
                    <Edit3 className="w-3 h-3" />
                    <span>{isEditingBody ? 'Done' : 'Edit'}</span>
                  </button>
                )}
                <button
                  type="button"
                  onClick={() => handleCopy('body', activeBody, 'Copied payload')}
                  className="px-2 py-1 rounded bg-[#1c2025] hover:bg-[#262a30] text-[#e0e2ea] font-mono text-[11px] flex items-center gap-1 cursor-pointer"
                >
                  {copiedId === 'body' ? (
                    <Check className="w-3 h-3 text-[#4edea3]" />
                  ) : (
                    <Copy className="w-3 h-3" />
                  )}
                  <span>{copiedId === 'body' ? 'Copied' : 'Copy'}</span>
                </button>
              </div>
            </div>

            {activeBody ? (
              <SyntaxCodeBlock
                code={activeBody}
                mode="json"
                editable={isEditingBody && direction === 'request'}
                onChange={(val) => setEditedBody(val)}
              />
            ) : (
              <div className="rounded-lg bg-[#0a0e13] py-6 text-center text-xs text-[#869397] font-mono">
                Empty {direction} payload (0 bytes)
              </div>
            )}

            <div className="flex items-center justify-between text-[10px] font-mono text-[#869397] pt-0.5">
              <span className="flex items-center gap-1">
                <Lock className="w-3 h-3" />
                {isEditingBody
                  ? 'EDITING — Send to Repeater to replay modified payload'
                  : "READ ONLY — Tap 'Edit' to craft repeater payload"}
              </span>
              <span className="tabular-nums">{activeBody.length} bytes</span>
            </div>
          </div>
        )}

        {subTab === 'cookies' && (
          <div className="bg-[#181c21] rounded-lg p-3 space-y-2">
            <div className="flex items-center justify-between pb-1.5 border-b border-[#262a30]">
              <span className="font-mono text-[11px] text-[#4cd7f6] tracking-wider uppercase font-semibold">
                COOKIES
              </span>
              <span className="font-mono text-[10px] text-[#869397] tabular-nums">
                {cookies.length} active
              </span>
            </div>
            {cookies.length === 0 ? (
              <div className="py-6 text-center text-xs text-[#869397]">
                No cookies captured for this transaction
              </div>
            ) : (
              <div className="flex flex-col gap-1.5">
                {cookies.map((c) => (
                  <div key={c.name} className="p-2 rounded bg-[#0a0e13] flex flex-col gap-0.5">
                    <div className="flex items-center justify-between">
                      <span className="font-mono text-[11px] font-semibold text-[#4cd7f6]">
                        {c.name}
                      </span>
                      {c.attributes && (
                        <span className="font-mono text-[10px] text-[#869397]">{c.attributes}</span>
                      )}
                    </div>
                    <span className="font-mono text-[12px] text-[#4edea3] break-all">{c.value}</span>
                  </div>
                ))}
              </div>
            )}
          </div>
        )}

        {subTab === 'raw' && (
          <div className="space-y-3">
            <div className="bg-[#181c21] rounded-lg p-3 space-y-2">
              <div className="flex items-center justify-between">
                <span className="font-mono text-[11px] text-[#4cd7f6] tracking-wider uppercase font-semibold">
                  RAW HTTP STREAM
                </span>
                <button
                  type="button"
                  onClick={() => handleCopy('raw', rawHttpText, 'Copied raw HTTP')}
                  className="px-2 py-1 rounded bg-[#1c2025] hover:bg-[#262a30] text-[#e0e2ea] font-mono text-[11px] flex items-center gap-1 cursor-pointer"
                >
                  <Copy className="w-3 h-3" />
                  <span>Copy</span>
                </button>
              </div>
              <SyntaxCodeBlock code={rawHttpText} mode="http" />
            </div>

            <div className="bg-[#181c21] rounded-lg p-3 space-y-2">
              <div className="flex items-center justify-between">
                <div className="flex items-center gap-2">
                  <span className="font-mono text-[11px] text-[#4cd7f6] tracking-wider uppercase font-semibold">
                    HEX STREAM PREVIEW
                  </span>
                  <span className="font-mono text-[10px] text-[#869397]">Offset 0x0000</span>
                </div>
                <span className="font-mono text-[10px] text-[#4edea3] flex items-center gap-1">
                  <span className="w-1.5 h-1.5 rounded-full bg-[#4edea3]" />
                  UTF-8
                </span>
              </div>
              <SyntaxCodeBlock code={activeBody || rawHttpText} mode="hex" />
            </div>
          </div>
        )}
      </div>

      {/* Sticky Tactical Actions Toolbar */}
      <div className="shrink-0 px-3 py-2.5 bg-[#0a0e13]/95 border-t border-[#262a30] flex items-center gap-2">
        <button
          type="button"
          onClick={() => handleCopy('curl', buildCurl(), 'Copied cURL command')}
          className="py-2 px-3 rounded-lg bg-[#1c2025] hover:bg-[#262a30] text-[#e0e2ea] font-mono text-[12px] font-semibold flex items-center justify-center gap-1.5 active:scale-95 transition-all cursor-pointer shrink-0"
        >
          <Terminal className="w-4 h-4 text-[#4cd7f6]" />
          <span>cURL</span>
        </button>

        <button
          type="button"
          onClick={() =>
            handleCopy(
              'all',
              `${tx.method} https://${tx.host}${tx.path}\n${activeBody}`,
              'Copied request summary'
            )
          }
          className="py-2 px-3 rounded-lg bg-[#1c2025] hover:bg-[#262a30] text-[#e0e2ea] font-mono text-[12px] font-semibold flex items-center justify-center gap-1.5 active:scale-95 transition-all cursor-pointer shrink-0"
        >
          <Copy className="w-4 h-4" />
          <span className="hidden sm:inline">Copy</span>
        </button>

        <button
          type="button"
          onClick={() =>
            sendToRepeater(
              editedBody !== null ? { ...tx, requestBody: editedBody } : tx
            )
          }
          className="flex-1 py-2 px-4 rounded-lg bg-[#4cd7f6] hover:bg-[#3cc7e6] text-[#003640] text-[13px] font-bold flex items-center justify-center gap-1.5 active:scale-[0.98] transition-all cursor-pointer shadow-md"
        >
          <Repeat className="w-4 h-4" />
          <span className="truncate">Send to Repeater</span>
        </button>

        <button
          type="button"
          onClick={() => deleteTransaction(tx.id)}
          aria-label="Delete packet"
          className="w-10 h-9 rounded-lg bg-[#1c2025] hover:bg-[#93000a]/40 text-[#ffb4ab] flex items-center justify-center active:scale-95 transition-all cursor-pointer shrink-0"
        >
          <Trash2 className="w-4 h-4" />
        </button>
      </div>
    </div>
  );
};
