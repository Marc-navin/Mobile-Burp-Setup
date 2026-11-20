import React, { useState } from 'react';
import {
  ArrowLeft,
  BadgeCheck,
  Check,
  CheckCircle2,
  ChevronDown,
  ChevronRight,
  Code2,
  Copy,
  Download,
  Eye,
  Fingerprint,
  Info,
  RefreshCw,
  ShieldCheck,
  Smartphone,
  X,
} from 'lucide-react';
import { useProxy } from '../context/ProxyContext';

export const HttpsCaView: React.FC = () => {
  const {
    settings,
    updateSetting,
    caDetails,
    installToSystemStore,
    regenerateCa,
    copyText,
    goBack,
    showToast,
  } = useProxy();

  const [detailsOpen, setDetailsOpen] = useState(true);
  const [copiedHash, setCopiedHash] = useState<'sha256' | 'sha1' | null>(null);
  const [showKeystoreModal, setShowKeystoreModal] = useState(false);

  const handleCopyHash = (kind: 'sha256' | 'sha1', value: string) => {
    copyText(value, `Copied ${kind.toUpperCase()} fingerprint`);
    setCopiedHash(kind);
    window.setTimeout(() => setCopiedHash(null), 1500);
  };

  const handleExportPem = () => {
    const pemContent = [
      '-----BEGIN CERTIFICATE-----',
      'MIIDXTCCAkWgAwIBAgIJAF8qkIsRQt4JMA0GCSqGSIb3DQEBCwUAMEUxCzAJBgNV',
      'BAYTAlVTMR0wGwYDVQQKDBRNb2JpbGUgQnVycCBTZWN1cml0eTEXMBUGA1UEAwwO',
      'TW9iaWxlIEJ1cnAgQ0EwHhcNMjYwOTE5MDAwMDAwWhcNMzYwOTE3MjM1OTU5WjBF',
      '-----END CERTIFICATE-----',
    ].join('\n');
    const blob = new Blob([pemContent], { type: 'application/x-pem-file' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = 'mobile-burp-dev-ca.pem';
    a.click();
    URL.revokeObjectURL(url);
    showToast('Exported mobile-burp-dev-ca.pem');
  };

  return (
    <div className="flex-1 overflow-y-auto w-full bg-[#101419] px-3 py-3 pb-8">
      <div className="max-w-4xl mx-auto flex flex-col gap-3">
        {/* Desktop Back Breadcrumb */}
        <div className="hidden lg:flex items-center justify-between pb-1">
          <button
            type="button"
            onClick={goBack}
            className="flex items-center gap-2 text-xs font-mono text-[#bcc9cd] hover:text-[#4cd7f6] cursor-pointer"
          >
            <ArrowLeft className="w-4 h-4" />
            <span>Back to Settings</span>
          </button>
          <span className="font-mono text-[11px] text-[#869397] uppercase tracking-wider">
            SETTINGS / HTTPS &amp; CA
          </span>
        </div>

        {/* 1. TOP STATUS & TOGGLE CARD */}
        <div className="flex flex-col bg-[#181c21] rounded-lg p-3 shadow-sm gap-2.5">
          <div className="flex items-center justify-between">
            <div className="flex items-center gap-1.5">
              <ShieldCheck className="w-4 h-4 text-[#4cd7f6]" />
              <span className="font-mono text-[10px] uppercase tracking-wider text-[#bcc9cd] font-semibold">
                HTTPS INTERCEPTION
              </span>
            </div>
            <div
              className={`flex items-center gap-1.5 px-2 py-0.5 rounded-full font-mono text-[10px] font-semibold tracking-wider ${
                settings.httpsInterception
                  ? 'bg-[#00a572]/20 text-[#4edea3]'
                  : 'bg-[#31353b] text-[#bcc9cd]'
              }`}
            >
              <span
                className={`w-1.5 h-1.5 rounded-full ${
                  settings.httpsInterception ? 'bg-[#4edea3] animate-pulse' : 'bg-[#869397]'
                }`}
              />
              <span>{settings.httpsInterception ? 'ENABLED' : 'DISABLED'}</span>
            </div>
          </div>

          <div className="flex items-start justify-between gap-3">
            <div className="flex flex-col flex-1 min-w-0">
              <span className="text-[15px] text-[#e0e2ea] font-semibold">Active MITM Engine</span>
              <p className="text-[11px] text-[#bcc9cd] mt-0.5 leading-snug">
                Intercept, inspect, and dynamically re-sign TLS requests using the local root CA.
              </p>
            </div>
            <button
              type="button"
              role="switch"
              aria-checked={settings.httpsInterception}
              aria-label="Toggle Active MITM Engine"
              onClick={() => updateSetting('httpsInterception', !settings.httpsInterception)}
              className={`relative inline-flex h-6 w-11 shrink-0 cursor-pointer items-center rounded-full transition-colors duration-200 ${
                settings.httpsInterception ? 'bg-[#00a572]' : 'bg-[#31353b]'
              }`}
            >
              <span
                className={`inline-block h-4 w-4 transform rounded-full bg-[#101419] shadow-md transition-transform duration-200 ${
                  settings.httpsInterception ? 'translate-x-6' : 'translate-x-1 bg-[#e0e2ea]'
                }`}
              />
            </button>
          </div>

          <div className="flex items-center gap-2 px-2.5 py-2 rounded bg-[#262a30] text-[#bcc9cd] text-[11px] leading-tight">
            <Info className="w-4 h-4 text-[#4cd7f6] shrink-0" />
            <span>
              Client apps must trust this CA via User Store, Magisk, or Network Security Config.
            </span>
          </div>
        </div>

        <div className="grid grid-cols-1 lg:grid-cols-2 gap-3">
          {/* 2. SYSTEM TRUST / INSTALLATION STATUS */}
          <div className="flex flex-col bg-[#181c21] rounded-lg p-3 shadow-sm gap-2.5">
            <div className="flex items-center justify-between">
              <span className="font-mono text-[10px] text-[#869397] uppercase tracking-wider font-semibold">
                SYSTEM TRUST • ANDROID KEYSTORE
              </span>
              <span className="font-mono text-[10px] text-[#4cd7f6]">API 24+</span>
            </div>

            <div className="flex items-center justify-between bg-[#1c2025] p-2.5 rounded">
              <div className="flex items-center gap-2.5">
                <CheckCircle2 className="w-5 h-5 text-[#4edea3] shrink-0" />
                <div className="flex flex-col">
                  <span className="text-[12px] text-[#e0e2ea] font-semibold">
                    Certificate Installation
                  </span>
                  <span className="text-[10px] text-[#bcc9cd]">User Credential Storage</span>
                </div>
              </div>
              <span className="font-mono text-[11px] text-[#4edea3] bg-[#4edea3]/10 px-2 py-0.5 rounded font-semibold">
                ● Installed
              </span>
            </div>

            <div className="flex flex-col gap-0.5 px-1">
              <span className="font-mono text-[10px] text-[#869397] uppercase tracking-wider">
                Target Scope
              </span>
              <span className="font-mono text-[11px] text-[#bcc9cd] truncate">
                Android 7.0+ Network Security Config or Root System Store
              </span>
            </div>

            <button
              type="button"
              onClick={installToSystemStore}
              className="w-full h-9 rounded bg-[#262a30] hover:bg-[#31353b] active:bg-[#36393f] transition-colors flex items-center justify-center gap-2 text-[#4cd7f6] text-[12px] font-semibold cursor-pointer mt-auto"
            >
              <Smartphone className="w-4 h-4" />
              <span>
                {caDetails.installedToSystemStore
                  ? 'SYSTEM STORE MOUNTED (MAGISK)'
                  : 'INSTALL TO SYSTEM STORE (ROOT / MAGISK)'}
              </span>
            </button>
          </div>

          {/* 3. CERTIFICATE AUTHORITY OVERVIEW */}
          <div className="flex flex-col bg-[#181c21] rounded-lg p-3 shadow-sm gap-1">
            <div className="flex items-center justify-between pb-1">
              <span className="font-mono text-[10px] text-[#869397] uppercase tracking-wider font-semibold">
                CERTIFICATE AUTHORITY • ROOT SPEC
              </span>
              <BadgeCheck className="w-4 h-4 text-[#869397]" />
            </div>

            {[
              { label: 'Common Name', value: caDetails.commonName, accent: false, bold: true },
              { label: 'Standard', value: caDetails.standard },
              { label: 'Key Algorithm', value: caDetails.keyAlgorithm, cyan: true, bold: true },
              { label: 'Issuer Organization', value: caDetails.issuerOrg },
              { label: 'Subject Name', value: caDetails.subjectName },
            ].map((row) => (
              <div
                key={row.label}
                className="flex items-center justify-between py-1.5 border-b border-[#31353b]/40 gap-2"
              >
                <span className="text-[11px] text-[#bcc9cd] shrink-0">{row.label}</span>
                <span
                  className={`font-mono text-[11px] truncate ${
                    row.cyan ? 'text-[#4cd7f6]' : 'text-[#e0e2ea]'
                  } ${row.bold ? 'font-semibold' : ''}`}
                >
                  {row.value}
                </span>
              </div>
            ))}

            <div className="flex items-center justify-between pt-1.5">
              <span className="text-[11px] text-[#bcc9cd]">Trust Status</span>
              <span className="font-mono text-[11px] text-[#4edea3] bg-[#4edea3]/10 px-2 py-0.5 rounded font-semibold">
                {caDetails.trustStatus}
              </span>
            </div>
          </div>
        </div>

        {/* 4. CERTIFICATE VALIDITY */}
        <div className="flex flex-col bg-[#181c21] rounded-lg p-3 shadow-sm gap-2">
          <div className="flex items-center justify-between">
            <span className="font-mono text-[10px] text-[#869397] uppercase tracking-wider font-semibold">
              VALIDITY PERIOD
            </span>
            <span className="font-mono text-[10px] text-[#4edea3] bg-[#4edea3]/10 px-2 py-0.5 rounded font-semibold tabular-nums">
              VALID ({caDetails.daysRemaining.toLocaleString()} DAYS REMAINING)
            </span>
          </div>
          <div className="grid grid-cols-2 gap-2 bg-[#1c2025] p-2.5 rounded tabular-nums">
            <div className="flex flex-col">
              <span className="font-mono text-[10px] text-[#869397] uppercase">Not Before</span>
              <span className="font-mono text-[12px] text-[#e0e2ea] mt-0.5">
                {caDetails.notBefore.split(' ')[0]}
              </span>
              <span className="font-mono text-[10px] text-[#bcc9cd]">00:00:00 UTC</span>
            </div>
            <div className="flex flex-col">
              <span className="font-mono text-[10px] text-[#869397] uppercase">Not After</span>
              <span className="font-mono text-[12px] text-[#e0e2ea] mt-0.5">
                {caDetails.notAfter.split(' ')[0]}
              </span>
              <span className="font-mono text-[10px] text-[#bcc9cd]">23:59:59 UTC</span>
            </div>
          </div>
        </div>

        {/* 5. CERTIFICATE FINGERPRINTS */}
        <div className="flex flex-col bg-[#181c21] rounded-lg p-3 shadow-sm gap-2.5">
          <div className="flex items-center justify-between">
            <span className="font-mono text-[10px] text-[#869397] uppercase tracking-wider font-semibold">
              FINGERPRINTS • CRYPTOGRAPHIC HASH
            </span>
            <Fingerprint className="w-4 h-4 text-[#869397]" />
          </div>

          <div className="flex flex-col gap-1">
            <div className="flex items-center justify-between">
              <span className="font-mono text-[10px] text-[#bcc9cd] uppercase font-semibold">
                SHA-256 FINGERPRINT
              </span>
              <button
                type="button"
                onClick={() => handleCopyHash('sha256', caDetails.sha256)}
                className="flex items-center gap-1 text-[#4cd7f6] hover:text-[#acedff] font-mono text-[10px] px-2 py-0.5 rounded bg-[#262a30] transition-colors cursor-pointer"
              >
                {copiedHash === 'sha256' ? (
                  <>
                    <Check className="w-3 h-3 text-[#4edea3]" />
                    <span>COPIED</span>
                  </>
                ) : (
                  <>
                    <Copy className="w-3 h-3" />
                    <span>COPY</span>
                  </>
                )}
              </button>
            </div>
            <div className="bg-[#0a0e13] p-2.5 rounded select-all font-mono text-[11px] leading-relaxed text-[#e0e2ea] break-all">
              {caDetails.sha256}
            </div>
          </div>

          <div className="flex flex-col gap-1 pt-0.5">
            <div className="flex items-center justify-between">
              <span className="font-mono text-[10px] text-[#bcc9cd] uppercase font-semibold">
                SHA-1 FINGERPRINT
              </span>
              <button
                type="button"
                onClick={() => handleCopyHash('sha1', caDetails.sha1)}
                className="flex items-center gap-1 text-[#4cd7f6] hover:text-[#acedff] font-mono text-[10px] px-2 py-0.5 rounded bg-[#262a30] transition-colors cursor-pointer"
              >
                {copiedHash === 'sha1' ? (
                  <>
                    <Check className="w-3 h-3 text-[#4edea3]" />
                    <span>COPIED</span>
                  </>
                ) : (
                  <>
                    <Copy className="w-3 h-3" />
                    <span>COPY</span>
                  </>
                )}
              </button>
            </div>
            <div className="bg-[#0a0e13] p-2.5 rounded select-all font-mono text-[11px] leading-relaxed text-[#e0e2ea] break-all">
              {caDetails.sha1}
            </div>
          </div>
        </div>

        {/* 6. CERTIFICATE ACTIONS & 7. TECHNICAL DETAILS */}
        <div className="grid grid-cols-1 lg:grid-cols-2 gap-3">
          <div className="flex flex-col bg-[#181c21] rounded-lg p-3 shadow-sm gap-1">
            <span className="font-mono text-[10px] text-[#869397] uppercase tracking-wider font-semibold pb-1">
              CERTIFICATE ACTIONS
            </span>

            <button
              type="button"
              onClick={() => setShowKeystoreModal(true)}
              className="w-full flex items-center justify-between p-2 rounded hover:bg-[#1c2025] active:bg-[#262a30] transition-colors text-left cursor-pointer"
            >
              <div className="flex items-center gap-2.5">
                <Eye className="w-5 h-5 text-[#4cd7f6] shrink-0" />
                <div className="flex flex-col">
                  <span className="text-[14px] font-semibold text-[#e0e2ea]">
                    View in Android Keystore
                  </span>
                  <span className="text-[11px] text-[#bcc9cd]">
                    Inspect installed certificate via system credentials
                  </span>
                </div>
              </div>
              <ChevronRight className="w-4 h-4 text-[#869397]" />
            </button>

            <button
              type="button"
              onClick={handleExportPem}
              className="w-full flex items-center justify-between p-2 rounded hover:bg-[#1c2025] active:bg-[#262a30] transition-colors text-left cursor-pointer"
            >
              <div className="flex items-center gap-2.5">
                <Download className="w-5 h-5 text-[#4edea3] shrink-0" />
                <div className="flex flex-col">
                  <span className="text-[14px] font-semibold text-[#e0e2ea]">
                    Export Certificate (.der / .pem)
                  </span>
                  <span className="text-[11px] text-[#bcc9cd]">
                    Save public cert to local device storage
                  </span>
                </div>
              </div>
              <ChevronRight className="w-4 h-4 text-[#869397]" />
            </button>

            <button
              type="button"
              onClick={regenerateCa}
              className="w-full flex items-center justify-between p-2 rounded hover:bg-[#1c2025] active:bg-[#262a30] transition-colors text-left cursor-pointer"
            >
              <div className="flex items-center gap-2.5">
                <RefreshCw className="w-5 h-5 text-[#ffb95f] shrink-0" />
                <div className="flex flex-col">
                  <span className="text-[14px] font-semibold text-[#ffb95f]">
                    Regenerate Development CA
                  </span>
                  <span className="text-[11px] text-[#bcc9cd]">
                    Generate fresh private key &amp; invalidate prior trust
                  </span>
                </div>
              </div>
              <ChevronRight className="w-4 h-4 text-[#ffb95f]" />
            </button>
          </div>

          <div className="flex flex-col bg-[#181c21] rounded-lg p-3 shadow-sm gap-1">
            <button
              type="button"
              onClick={() => setDetailsOpen((v) => !v)}
              className="w-full flex items-center justify-between text-left cursor-pointer"
            >
              <div className="flex items-center gap-2">
                <Code2 className="w-4 h-4 text-[#4cd7f6]" />
                <div className="flex flex-col">
                  <span className="font-mono text-[10px] text-[#869397] uppercase tracking-wider font-semibold">
                    X.509 TECHNICAL DETAILS
                  </span>
                  <span className="text-[13px] text-[#e0e2ea] font-semibold">
                    Certificate Raw Metadata
                  </span>
                </div>
              </div>
              <ChevronDown
                className={`w-5 h-5 text-[#869397] transition-transform duration-200 ${
                  detailsOpen ? 'rotate-0' : '-rotate-90'
                }`}
              />
            </button>

            {detailsOpen && (
              <div className="flex flex-col gap-1 pt-2">
                {[
                  { label: 'Version', value: '3 (0x2)' },
                  { label: 'Serial Number', value: caDetails.serialNumber, cyan: true },
                  { label: 'Signature Algorithm', value: caDetails.signatureAlgorithm },
                  { label: 'Public Key', value: caDetails.publicKeySpec },
                  { label: 'Basic Constraints', value: caDetails.basicConstraints, green: true },
                  { label: 'Key Usage', value: caDetails.keyUsage },
                ].map((item) => (
                  <div
                    key={item.label}
                    className="flex items-center justify-between py-1 border-b border-[#31353b]/30 last:border-b-0"
                  >
                    <span className="text-[11px] text-[#bcc9cd]">{item.label}</span>
                    <span
                      className={`font-mono text-[11px] ${
                        item.cyan
                          ? 'text-[#4cd7f6]'
                          : item.green
                            ? 'text-[#4edea3]'
                            : 'text-[#e0e2ea]'
                      }`}
                    >
                      {item.value}
                    </span>
                  </div>
                ))}
              </div>
            )}
          </div>
        </div>

        {/* 8. COMPLIANCE FOOTER NOTE */}
        <div className="flex items-start gap-2 p-3 rounded-lg bg-[#0a0e13] text-[#bcc9cd]">
          <BadgeCheck className="w-4 h-4 text-[#869397] shrink-0 mt-0.5" />
          <p className="text-[11px] leading-snug">
            Use this development CA only on devices and applications you are authorized to test.
            Never distribute the private key.
          </p>
        </div>
      </div>

      {/* Android Keystore Modal */}
      {showKeystoreModal && (
        <div className="fixed inset-0 z-50 bg-black/70 backdrop-blur-sm flex items-center justify-center p-4">
          <div className="w-full max-w-md rounded-xl bg-[#181c21] border border-[#3d494c] p-4 space-y-3 shadow-2xl">
            <div className="flex items-center justify-between">
              <span className="font-mono text-xs font-semibold text-[#4cd7f6] uppercase">
                Android Keystore Credential
              </span>
              <button
                type="button"
                onClick={() => setShowKeystoreModal(false)}
                className="text-[#869397] hover:text-[#e0e2ea] cursor-pointer"
              >
                <X className="w-4 h-4" />
              </button>
            </div>
            <div className="p-3 rounded-lg bg-[#0a0e13] font-mono text-[11px] space-y-1.5 text-[#e0e2ea]">
              <div>
                <span className="text-[#869397]">Alias:</span> user:mobileburp_dev_ca
              </div>
              <div>
                <span className="text-[#869397]">Subject:</span> {caDetails.subjectName}
              </div>
              <div>
                <span className="text-[#869397]">Serial:</span> {caDetails.serialNumber}
              </div>
              <div>
                <span className="text-[#869397]">Store:</span>{' '}
                {caDetails.installedToSystemStore ? 'System + User' : 'User Credential Store'}
              </div>
            </div>
            <button
              type="button"
              onClick={() => setShowKeystoreModal(false)}
              className="w-full py-2 rounded-lg bg-[#4cd7f6] text-[#003640] font-semibold text-xs cursor-pointer"
            >
              Close
            </button>
          </div>
        </div>
      )}
    </div>
  );
};
