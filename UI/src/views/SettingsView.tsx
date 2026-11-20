import React, { useState } from 'react';
import {
  BadgeCheck,
  ChevronRight,
  Eraser,
  Pencil,
  RefreshCw,
  Shield,
  Trash2,
} from 'lucide-react';
import { useProxy } from '../context/ProxyContext';

interface ToggleProps {
  checked: boolean;
  onChange: (next: boolean) => void;
  label: string;
}

const SwitchToggle: React.FC<ToggleProps> = ({ checked, onChange, label }) => (
  <button
    type="button"
    role="switch"
    aria-checked={checked}
    aria-label={label}
    onClick={() => onChange(!checked)}
    className={`w-10 h-5 rounded-full relative flex items-center px-0.5 transition-colors cursor-pointer shrink-0 ${
      checked ? 'bg-[#4cd7f6]' : 'bg-[#31353b]'
    }`}
  >
    <span
      className={`w-4 h-4 rounded-full bg-[#003640] shadow transform transition-transform ${
        checked ? 'translate-x-5' : 'translate-x-0 bg-[#e0e2ea]'
      }`}
    />
  </button>
);

export const SettingsView: React.FC = () => {
  const {
    settings,
    updateSetting,
    transactions,
    totalPacketsCount,
    clearTransactions,
    navigate,
    showToast,
  } = useProxy();

  const [editingPort, setEditingPort] = useState(false);
  const [portDraft, setPortDraft] = useState(String(settings.port));

  const savePort = () => {
    const parsed = parseInt(portDraft, 10);
    if (!Number.isNaN(parsed) && parsed >= 1024 && parsed <= 65535) {
      updateSetting('port', parsed);
      showToast(`Listener port updated to :${parsed}`);
    } else {
      setPortDraft(String(settings.port));
      showToast('Enter a valid port (1024–65535)');
    }
    setEditingPort(false);
  };

  return (
    <div className="flex-1 overflow-y-auto w-full bg-[#101419] px-3 py-3 pb-6">
      <div className="max-w-5xl mx-auto flex flex-col gap-3">
        {/* Top Status Badges + Daemon Config Card */}
        <div className="flex items-center gap-2 overflow-x-auto no-scrollbar pb-0.5">
          <div className="flex items-center gap-1.5 px-2.5 py-1 rounded-full bg-[#00a572]/20 text-[#4edea3] font-mono text-[11px] shrink-0">
            <span
              className={`w-1.5 h-1.5 rounded-full ${
                settings.proxyEnabled ? 'bg-[#4edea3] animate-pulse' : 'bg-[#ffb4ab]'
              }`}
            />
            <span className="font-semibold uppercase tracking-wider">
              PROXY • {settings.proxyEnabled ? `RUNNING (${settings.port})` : 'STOPPED'}
            </span>
          </div>
          <button
            type="button"
            onClick={() => updateSetting('interceptEnabled', !settings.interceptEnabled)}
            className="flex items-center gap-1.5 px-2.5 py-1 rounded-full bg-[#e79400]/20 text-[#ffb95f] font-mono text-[11px] shrink-0 cursor-pointer"
          >
            <span className="w-1.5 h-1.5 rounded-full bg-[#ffb95f]" />
            <span className="font-semibold uppercase tracking-wider">
              INTERCEPT • {settings.interceptEnabled ? 'ON' : 'OFF'}
            </span>
          </button>
          <div className="flex items-center gap-1 px-2.5 py-1 rounded-full bg-[#31353b] text-[#4cd7f6] font-mono text-[11px] shrink-0 tabular-nums">
            <span className="text-[#bcc9cd]">CAP:</span>
            <span className="font-bold">{totalPacketsCount}</span>
          </div>
        </div>

        {/* Daemon Telemetry Summary */}
        <div className="flex flex-col bg-[#181c21] rounded-lg p-3 shadow-sm">
          <div className="flex items-center justify-between pb-2">
            <div className="flex items-center gap-1.5">
              <span className="font-mono text-[11px] text-[#4cd7f6] uppercase font-bold tracking-wider">
                SETTINGS
              </span>
              <span className="text-[#3d494c]">•</span>
              <span className="font-mono text-[11px] text-[#bcc9cd] font-medium">
                DAEMON CONFIG
              </span>
            </div>
            <div className="flex items-center gap-1.5 px-2 py-0.5 rounded-full bg-[#00a572]/20 text-[#4edea3]">
              <span className="w-1.5 h-1.5 rounded-full bg-[#4edea3] animate-pulse" />
              <span className="font-mono text-[10px] uppercase font-semibold tracking-wider tabular-nums">
                {settings.proxyEnabled ? `PROXY ON :${settings.port}` : 'PROXY OFF'}
              </span>
            </div>
          </div>

          <div className="grid grid-cols-3 gap-2 pt-2 bg-[#0a0e13] rounded-md p-2.5">
            <div className="flex flex-col min-w-0">
              <span className="font-mono text-[10px] text-[#869397] uppercase tracking-wider">
                Interface
              </span>
              <span className="font-mono text-[11px] text-[#e0e2ea] truncate font-medium tabular-nums">
                {settings.proxyMode.includes('0.0.0.0') ? '0.0.0.0' : '127.0.0.1'}:{settings.port}
              </span>
              <span className="font-mono text-[9px] text-[#4edea3] font-medium">
                {settings.proxyEnabled ? 'LISTENING' : 'IDLE'}
              </span>
            </div>
            <div className="flex flex-col min-w-0">
              <span className="font-mono text-[10px] text-[#869397] uppercase tracking-wider">
                Buffer
              </span>
              <span className="font-mono text-[11px] text-[#e0e2ea] truncate font-medium tabular-nums">
                3.4 MB
              </span>
              <span className="font-mono text-[9px] text-[#bcc9cd] tabular-nums">
                {settings.maxHistory} MAX REQS
              </span>
            </div>
            <div className="flex flex-col min-w-0">
              <span className="font-mono text-[10px] text-[#869397] uppercase tracking-wider">
                Engine
              </span>
              <span className="font-mono text-[11px] text-[#4cd7f6] truncate font-medium">
                BoringSSL 1.3
              </span>
              <span className="font-mono text-[9px] text-[#4edea3] font-medium">
                {settings.httpsInterception ? 'ACTIVE' : 'BYPASS'}
              </span>
            </div>
          </div>
        </div>

        {/* Settings Sections Grid (1-col mobile, 2-col desktop) */}
        <div className="grid grid-cols-1 xl:grid-cols-2 gap-3">
          {/* SECTION 1: PROXY */}
          <div className="flex flex-col bg-[#181c21] rounded-lg overflow-hidden shadow-sm">
            <div className="flex items-center justify-between px-3 py-2 bg-[#1c2025]">
              <span className="font-mono text-[11px] text-[#4cd7f6] font-bold tracking-wider">
                PROXY
              </span>
              <span className="font-mono text-[10px] text-[#869397]">SOCKET DAEMON</span>
            </div>

            <div className="flex items-center justify-between px-3 py-2.5 bg-[#181c21]">
              <div className="flex flex-col min-w-0 pr-2">
                <span className="text-[14px] font-semibold text-[#e0e2ea]">Proxy Status</span>
                <span className="text-[11px] text-[#bcc9cd] truncate">
                  Local interception daemon
                </span>
              </div>
              <div
                className={`flex items-center gap-1.5 px-2.5 py-0.5 rounded-full font-mono text-[11px] font-semibold ${
                  settings.proxyEnabled
                    ? 'bg-[#00a572]/20 text-[#4edea3]'
                    : 'bg-[#93000a]/30 text-[#ffb4ab]'
                }`}
              >
                <span
                  className={`w-1.5 h-1.5 rounded-full ${
                    settings.proxyEnabled ? 'bg-[#4edea3]' : 'bg-[#ffb4ab]'
                  }`}
                />
                <span>{settings.proxyEnabled ? 'Running' : 'Stopped'}</span>
              </div>
            </div>

            <div className="flex items-center justify-between px-3 py-2.5 bg-[#0a0e13]/60">
              <div className="flex flex-col min-w-0 pr-2">
                <span className="text-[14px] font-semibold text-[#e0e2ea]">Proxy Enabled</span>
                <span className="text-[11px] text-[#bcc9cd] truncate">
                  Route incoming system socket
                </span>
              </div>
              <SwitchToggle
                checked={settings.proxyEnabled}
                onChange={(v) => {
                  updateSetting('proxyEnabled', v);
                  showToast(v ? `Proxy started on :${settings.port}` : 'Proxy listener paused');
                }}
                label="Proxy Enabled"
              />
            </div>

            <div className="flex items-center justify-between px-3 py-2.5 bg-[#181c21]">
              <div className="flex flex-col min-w-0 pr-2">
                <span className="text-[14px] font-semibold text-[#e0e2ea]">Port</span>
                <span className="text-[11px] text-[#bcc9cd] truncate">Default listener port</span>
              </div>
              {editingPort ? (
                <form
                  onSubmit={(e) => {
                    e.preventDefault();
                    savePort();
                  }}
                  className="flex items-center gap-1"
                >
                  <input
                    type="number"
                    value={portDraft}
                    onChange={(e) => setPortDraft(e.target.value)}
                    onBlur={savePort}
                    autoFocus
                    className="w-20 px-2 py-1 rounded bg-[#0a0e13] border border-[#4cd7f6] font-mono text-xs text-[#4cd7f6] focus:outline-none tabular-nums"
                  />
                </form>
              ) : (
                <button
                  type="button"
                  onClick={() => {
                    setPortDraft(String(settings.port));
                    setEditingPort(true);
                  }}
                  className="flex items-center gap-1.5 px-2.5 py-1 rounded bg-[#31353b] text-[#4cd7f6] hover:bg-[#36393f] cursor-pointer tabular-nums"
                >
                  <span className="font-mono text-[11px] font-bold">{settings.port}</span>
                  <Pencil className="w-3 h-3 text-[#869397]" />
                </button>
              )}
            </div>

            <div className="flex items-center justify-between px-3 py-2.5 bg-[#0a0e13]/60">
              <div className="flex flex-col min-w-0 pr-2">
                <span className="text-[14px] font-semibold text-[#e0e2ea]">Proxy Mode</span>
                <span className="text-[11px] text-[#bcc9cd] truncate">Binding interface</span>
              </div>
              <button
                type="button"
                onClick={() =>
                  updateSetting(
                    'proxyMode',
                    settings.proxyMode === 'Local (127.0.0.1)'
                      ? 'All Interfaces (0.0.0.0)'
                      : 'Local (127.0.0.1)'
                  )
                }
                className="px-2.5 py-1 rounded bg-[#262a30] hover:bg-[#31353b] font-mono text-[11px] text-[#e0e2ea] cursor-pointer"
              >
                {settings.proxyMode}
              </button>
            </div>
          </div>

          {/* SECTION 2: HTTPS & TLS */}
          <div className="flex flex-col bg-[#181c21] rounded-lg overflow-hidden shadow-sm">
            <div className="flex items-center justify-between px-3 py-2 bg-[#1c2025]">
              <span className="font-mono text-[11px] text-[#4cd7f6] font-bold tracking-wider">
                HTTPS &amp; TLS
              </span>
              <span className="font-mono text-[10px] text-[#869397]">MITM CA RE-SIGNING</span>
            </div>

            <div className="flex items-center justify-between px-3 py-2.5 bg-[#181c21]">
              <div className="flex flex-col min-w-0 pr-2">
                <span className="text-[14px] font-semibold text-[#e0e2ea]">
                  HTTPS Interception
                </span>
                <span className="text-[11px] text-[#bcc9cd] truncate">
                  On-the-fly TLS decryption &amp; re-signing
                </span>
              </div>
              <button
                type="button"
                onClick={() => updateSetting('httpsInterception', !settings.httpsInterception)}
                className={`px-2.5 py-0.5 rounded-full font-mono text-[11px] font-semibold cursor-pointer ${
                  settings.httpsInterception
                    ? 'bg-[#00a572]/20 text-[#4edea3]'
                    : 'bg-[#31353b] text-[#bcc9cd]'
                }`}
              >
                {settings.httpsInterception ? 'Enabled' : 'Disabled'}
              </button>
            </div>

            <div className="flex items-center justify-between px-3 py-2.5 bg-[#0a0e13]/60">
              <div className="flex flex-col min-w-0 pr-2">
                <span className="text-[14px] font-semibold text-[#e0e2ea]">CA Certificate</span>
                <span className="text-[11px] text-[#bcc9cd] truncate">
                  PortSwigger / MobileBurp Root CA
                </span>
              </div>
              <div className="flex items-center gap-1 text-[#4edea3]">
                <BadgeCheck className="w-4 h-4" />
                <span className="font-mono text-[11px] font-semibold">User Trust</span>
              </div>
            </div>

            <div className="flex items-center justify-between px-3 py-2.5 bg-[#181c21]">
              <div className="flex flex-col min-w-0 pr-2">
                <span className="text-[14px] font-semibold text-[#e0e2ea]">
                  Certificate Authority
                </span>
                <span className="text-[11px] text-[#bcc9cd] truncate">
                  RSA 4096-bit • Expires 2035
                </span>
              </div>
              <span className="px-2 py-0.5 rounded bg-[#31353b] font-mono text-[11px] text-[#e0e2ea] font-medium">
                Active
              </span>
            </div>

            <button
              type="button"
              onClick={() => navigate('https-ca')}
              className="flex items-center justify-between px-3 py-2.5 bg-[#0a0e13]/60 hover:bg-[#262a30] text-left transition-colors cursor-pointer"
            >
              <div className="flex flex-col min-w-0 pr-2">
                <span className="text-[14px] font-semibold text-[#e0e2ea]">View Certificate</span>
                <span className="text-[11px] text-[#bcc9cd] truncate">
                  Fingerprints &amp; SHA-256 hashes
                </span>
              </div>
              <ChevronRight className="w-4 h-4 text-[#869397] shrink-0" />
            </button>

            <button
              type="button"
              onClick={() => navigate('https-ca')}
              className="flex items-center justify-between px-3 py-2.5 bg-[#181c21] hover:bg-[#262a30] text-left transition-colors cursor-pointer"
            >
              <div className="flex items-center gap-2.5 min-w-0">
                <Shield className="w-5 h-5 text-[#4cd7f6] shrink-0" />
                <div className="flex flex-col min-w-0">
                  <span className="text-[14px] font-semibold text-[#e0e2ea]">
                    Certificate Details &amp; Export
                  </span>
                  <span className="text-[11px] text-[#bcc9cd] truncate">
                    Export .der / .pem for system store
                  </span>
                </div>
              </div>
              <ChevronRight className="w-4 h-4 text-[#869397] shrink-0" />
            </button>
          </div>

          {/* SECTION 3: TRAFFIC & CAPTURE */}
          <div className="flex flex-col bg-[#181c21] rounded-lg overflow-hidden shadow-sm">
            <div className="flex items-center justify-between px-3 py-2 bg-[#1c2025]">
              <span className="font-mono text-[11px] text-[#4cd7f6] font-bold tracking-wider">
                TRAFFIC &amp; CAPTURE
              </span>
              <span className="font-mono text-[10px] text-[#869397]">HISTORY &amp; STREAM</span>
            </div>

            <div className="flex items-center justify-between px-3 py-2.5 bg-[#181c21]">
              <div className="flex flex-col min-w-0 pr-2">
                <span className="text-[14px] font-semibold text-[#e0e2ea]">Capture Traffic</span>
                <span className="text-[11px] text-[#bcc9cd] truncate">
                  Stream HTTP/1.1 &amp; HTTP/2 flows into History
                </span>
              </div>
              <SwitchToggle
                checked={settings.captureTraffic}
                onChange={(v) => updateSetting('captureTraffic', v)}
                label="Capture Traffic"
              />
            </div>

            <div className="flex items-center justify-between px-3 py-2.5 bg-[#0a0e13]/60">
              <div className="flex flex-col min-w-0 pr-2">
                <span className="text-[14px] font-semibold text-[#e0e2ea]">Maximum History</span>
                <span className="text-[11px] text-[#bcc9cd] truncate">
                  Buffer FIFO eviction threshold
                </span>
              </div>
              <button
                type="button"
                onClick={() => {
                  const next = settings.maxHistory === 500 ? 1000 : settings.maxHistory === 1000 ? 250 : 500;
                  updateSetting('maxHistory', next);
                }}
                className="px-2.5 py-0.5 rounded bg-[#31353b] hover:bg-[#36393f] font-mono text-[11px] text-[#4cd7f6] font-bold cursor-pointer tabular-nums"
              >
                {settings.maxHistory} requests
              </button>
            </div>

            <div className="flex items-center justify-between px-3 py-2.5 bg-[#181c21]">
              <div className="flex flex-col min-w-0 pr-2">
                <span className="text-[14px] font-semibold text-[#e0e2ea]">
                  Clear Traffic History
                </span>
                <span className="text-[11px] text-[#bcc9cd] truncate tabular-nums">
                  Purge all {transactions.length} recorded packets
                </span>
              </div>
              <button
                type="button"
                onClick={clearTransactions}
                className="flex items-center gap-1 px-2.5 py-1.5 rounded bg-[#93000a]/25 hover:bg-[#93000a]/45 text-[#ffb4ab] font-mono text-[11px] font-semibold transition-colors cursor-pointer"
              >
                <Trash2 className="w-3.5 h-3.5" />
                <span>Clear Traffic</span>
              </button>
            </div>

            <div className="flex items-center justify-between px-3 py-2.5 bg-[#0a0e13]/60">
              <div className="flex flex-col min-w-0 pr-2">
                <span className="text-[14px] font-semibold text-[#e0e2ea]">Clear Debug Logs</span>
                <span className="text-[11px] text-[#bcc9cd] truncate">
                  Flush socket connection logs
                </span>
              </div>
              <button
                type="button"
                onClick={() => showToast('Socket debug logs flushed')}
                className="flex items-center gap-1 px-2.5 py-1.5 rounded bg-[#31353b] hover:bg-[#36393f] text-[#bcc9cd] font-mono text-[11px] font-semibold transition-colors cursor-pointer"
              >
                <Eraser className="w-3.5 h-3.5" />
                <span>Clear Logs</span>
              </button>
            </div>
          </div>

          {/* SECTION 4: INTEGRATED BROWSER */}
          <div className="flex flex-col bg-[#181c21] rounded-lg overflow-hidden shadow-sm">
            <div className="flex items-center justify-between px-3 py-2 bg-[#1c2025]">
              <span className="font-mono text-[11px] text-[#4cd7f6] font-bold tracking-wider">
                INTEGRATED BROWSER
              </span>
              <span className="font-mono text-[10px] text-[#869397]">WEBVIEW RUNTIME</span>
            </div>

            <div className="flex items-center justify-between px-3 py-2.5 bg-[#181c21]">
              <div className="flex flex-col min-w-0 pr-2">
                <span className="text-[14px] font-semibold text-[#e0e2ea]">User Agent</span>
                <span className="text-[11px] text-[#bcc9cd] truncate">
                  Default WebView client header
                </span>
              </div>
              <button
                type="button"
                onClick={() => {
                  const next =
                    settings.userAgent === 'MobileBurp/1.0'
                      ? 'Pixel8Pro/Android14'
                      : 'MobileBurp/1.0';
                  updateSetting('userAgent', next);
                }}
                className="px-2.5 py-0.5 rounded bg-[#31353b] hover:bg-[#36393f] font-mono text-[11px] text-[#4cd7f6] font-medium truncate max-w-[160px] cursor-pointer"
              >
                {settings.userAgent}
              </button>
            </div>

            <div className="flex items-center justify-between px-3 py-2.5 bg-[#0a0e13]/60">
              <div className="flex flex-col min-w-0 pr-2">
                <span className="text-[14px] font-semibold text-[#e0e2ea]">
                  JavaScript Execution
                </span>
                <span className="text-[11px] text-[#bcc9cd] truncate">
                  Allow client-side scripting in WebView
                </span>
              </div>
              <SwitchToggle
                checked={settings.jsEnabled}
                onChange={(v) => updateSetting('jsEnabled', v)}
                label="JavaScript Execution"
              />
            </div>

            <div className="flex items-center justify-between px-3 py-2.5 bg-[#181c21]">
              <div className="flex flex-col min-w-0 pr-2">
                <span className="text-[14px] font-semibold text-[#e0e2ea]">
                  Clear Browser Data
                </span>
                <span className="text-[11px] text-[#bcc9cd] truncate">
                  Purge cookies, storage &amp; cache
                </span>
              </div>
              <button
                type="button"
                onClick={() => showToast('WebView cookies & storage cleared')}
                className="flex items-center gap-1 px-2.5 py-1.5 rounded bg-[#31353b] hover:bg-[#36393f] text-[#bcc9cd] font-mono text-[11px] font-semibold transition-colors cursor-pointer"
              >
                <RefreshCw className="w-3.5 h-3.5" />
                <span>Clear Data</span>
              </button>
            </div>
          </div>

          {/* SECTION 5: APPEARANCE & DISPLAY */}
          <div className="flex flex-col bg-[#181c21] rounded-lg overflow-hidden shadow-sm">
            <div className="flex items-center justify-between px-3 py-2 bg-[#1c2025]">
              <span className="font-mono text-[11px] text-[#4cd7f6] font-bold tracking-wider">
                APPEARANCE &amp; DISPLAY
              </span>
              <span className="font-mono text-[10px] text-[#869397]">THEME PALETTE</span>
            </div>

            <div className="flex items-center justify-between px-3 py-2.5 bg-[#181c21]">
              <div className="flex flex-col min-w-0 pr-2">
                <span className="text-[14px] font-semibold text-[#e0e2ea]">Theme</span>
                <span className="text-[11px] text-[#bcc9cd] truncate">
                  Cybersecurity high-contrast mode
                </span>
              </div>
              <span className="px-2 py-0.5 rounded bg-[#31353b] font-mono text-[11px] text-[#e0e2ea]">
                Dark (Tactical)
              </span>
            </div>

            <div className="flex items-center justify-between px-3 py-2.5 bg-[#0a0e13]/60">
              <div className="flex flex-col min-w-0 pr-2">
                <span className="text-[14px] font-semibold text-[#e0e2ea]">Compact Mode</span>
                <span className="text-[11px] text-[#bcc9cd] truncate">
                  Maximized density for code &amp; headers
                </span>
              </div>
              <SwitchToggle
                checked={settings.compactMode}
                onChange={(v) => updateSetting('compactMode', v)}
                label="Compact Mode"
              />
            </div>

            <div className="flex items-center justify-between px-3 py-2.5 bg-[#181c21]">
              <div className="flex flex-col min-w-0 pr-2">
                <span className="text-[14px] font-semibold text-[#e0e2ea]">
                  Syntax Highlighting
                </span>
                <span className="text-[11px] text-[#bcc9cd] truncate">
                  Monospace HTTP headers &amp; JSON payloads
                </span>
              </div>
              <span className="px-2 py-0.5 rounded bg-[#31353b] font-mono text-[11px] text-[#4cd7f6] font-bold">
                {settings.syntaxTheme}
              </span>
            </div>
          </div>

          {/* SECTION 6: ABOUT & TELEMETRY */}
          <div className="flex flex-col bg-[#181c21] rounded-lg overflow-hidden shadow-sm">
            <div className="flex items-center justify-between px-3 py-2 bg-[#1c2025]">
              <span className="font-mono text-[11px] text-[#4cd7f6] font-bold tracking-wider">
                ABOUT &amp; TELEMETRY
              </span>
              <span className="font-mono text-[10px] text-[#869397]">BUILD ENGINE</span>
            </div>

            <div className="flex items-center justify-between px-3 py-2.5 bg-[#181c21]">
              <div className="flex flex-col min-w-0 pr-2">
                <span className="text-[14px] font-semibold text-[#e0e2ea]">Mobile Burp</span>
                <span className="text-[11px] text-[#bcc9cd] truncate">
                  Mobile HTTP/HTTPS security testing proxy
                </span>
              </div>
              <span className="px-2 py-0.5 rounded bg-[#31353b] font-mono text-[11px] text-[#4cd7f6] font-bold">
                v1.0.0-dev
              </span>
            </div>

            <div className="flex items-center justify-between px-3 py-2.5 bg-[#0a0e13]/60">
              <div className="flex flex-col min-w-0 pr-2">
                <span className="text-[14px] font-semibold text-[#e0e2ea]">
                  Build Architecture
                </span>
                <span className="text-[11px] text-[#bcc9cd] truncate">
                  Android aarch64 (arm64-v8a)
                </span>
              </div>
              <span className="px-2 py-0.5 rounded bg-[#e79400]/20 font-mono text-[11px] text-[#ffb95f] font-semibold">
                Development
              </span>
            </div>

            <div className="flex items-center justify-between px-3 py-2.5 bg-[#181c21]">
              <div className="flex flex-col min-w-0 pr-2">
                <span className="text-[14px] font-semibold text-[#e0e2ea]">Engine Core</span>
                <span className="text-[11px] text-[#bcc9cd] truncate">
                  Zero-copy packet relay engine
                </span>
              </div>
              <div className="flex items-center gap-1.5 text-[#4edea3]">
                <span className="w-1.5 h-1.5 rounded-full bg-[#4edea3]" />
                <span className="font-mono text-[11px] font-semibold">Ready</span>
              </div>
            </div>
          </div>
        </div>
      </div>
    </div>
  );
};
