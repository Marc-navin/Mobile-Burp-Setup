import React, { createContext, useCallback, useContext, useMemo, useState } from 'react';
import {
  INITIAL_CA_DETAILS,
  INITIAL_INTERCEPT_QUEUE,
  INITIAL_REPEATER_TABS,
  INITIAL_SETTINGS,
  INITIAL_TRANSACTIONS,
} from '../data/mockTraffic';
import {
  AppRoute,
  CaDetails,
  HttpHeader,
  HttpMethod,
  HttpTransaction,
  InterceptedRequest,
  ProxySettings,
  RepeaterTab,
} from '../types/proxy';

interface ProxyContextValue {
  route: AppRoute;
  navigate: (next: AppRoute) => void;
  goBack: () => void;

  // Traffic
  transactions: HttpTransaction[];
  selectedTransaction: HttpTransaction | null;
  selectTransaction: (id: string, openDetail?: boolean) => void;
  clearTransactions: () => void;
  deleteTransaction: (id: string) => void;
  totalPacketsCount: number;
  totalVolumeLabel: string;
  uniqueHostsCount: number;

  // Intercept
  interceptQueue: InterceptedRequest[];
  updateInterceptedBody: (id: string, body: string) => void;
  updateInterceptedHeader: (id: string, index: number, value: string) => void;
  forwardIntercepted: (id: string) => void;
  dropIntercepted: (id: string) => void;
  simulateIntercepted: () => void;

  // Repeater
  repeaterTabs: RepeaterTab[];
  activeRepeaterTab: RepeaterTab;
  setActiveRepeaterTabId: (id: string) => void;
  sendToRepeater: (tx: HttpTransaction) => void;
  updateRepeaterTab: (id: string, patch: Partial<RepeaterTab>) => void;
  dispatchRepeaterRequest: (id: string) => void;
  duplicateRepeaterTab: (id: string) => void;
  closeRepeaterTab: (id: string) => void;

  // Browser
  browserUrl: string;
  setBrowserUrl: (url: string) => void;
  cartCount: number;
  emitBrowserRequest: (opts: {
    method: HttpMethod;
    host: string;
    path: string;
    body?: string;
    status?: number;
    statusText?: string;
    responseBody?: string;
    addToCartDelta?: number;
  }) => void;

  // Settings & CA
  settings: ProxySettings;
  updateSetting: <K extends keyof ProxySettings>(key: K, value: ProxySettings[K]) => void;
  caDetails: CaDetails;
  installToSystemStore: () => void;
  regenerateCa: () => void;

  // Utilities
  toast: string | null;
  showToast: (msg: string) => void;
  copyText: (text: string, label?: string) => void;
}

const ProxyContext = createContext<ProxyContextValue | null>(null);

function nowTimeStr(): string {
  const d = new Date();
  const hh = String(d.getHours()).padStart(2, '0');
  const mm = String(d.getMinutes()).padStart(2, '0');
  const ss = String(d.getSeconds()).padStart(2, '0');
  return `${hh}:${mm}:${ss}`;
}

function formatSize(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
}

function randomHexSegment(bytes: number): string {
  const parts: string[] = [];
  for (let i = 0; i < bytes; i++) {
    parts.push(Math.floor(Math.random() * 256).toString(16).toUpperCase().padStart(2, '0'));
  }
  return parts.join(':');
}

export const ProxyProvider: React.FC<{ children: React.ReactNode }> = ({ children }) => {
  const [route, setRoute] = useState<AppRoute>('traffic');
  const [previousRoute, setPreviousRoute] = useState<AppRoute>('traffic');

  const [transactions, setTransactions] = useState<HttpTransaction[]>(INITIAL_TRANSACTIONS);
  const [selectedTransactionId, setSelectedTransactionId] = useState<string>(INITIAL_TRANSACTIONS[0].id);
  const [packetCounterOffset, setPacketCounterOffset] = useState<number>(117); // 11 initial + 117 = 128
  const [baseVolumeBytes, setBaseVolumeBytes] = useState<number>(1735000);
  const [nextSeq, setNextSeq] = useState<number>(44);

  const [interceptQueue, setInterceptQueue] = useState<InterceptedRequest[]>(INITIAL_INTERCEPT_QUEUE);
  const [repeaterTabs, setRepeaterTabs] = useState<RepeaterTab[]>(INITIAL_REPEATER_TABS);
  const [activeRepeaterTabId, setActiveRepeaterTabId] = useState<string>(INITIAL_REPEATER_TABS[0].id);

  const [settings, setSettings] = useState<ProxySettings>(INITIAL_SETTINGS);
  const [caDetails, setCaDetails] = useState<CaDetails>(INITIAL_CA_DETAILS);

  const [browserUrl, setBrowserUrl] = useState<string>('example.com/store/products');
  const [cartCount, setCartCount] = useState<number>(2);
  const [toast, setToast] = useState<string | null>(null);

  const showToast = useCallback((msg: string) => {
    setToast(msg);
    window.setTimeout(() => {
      setToast((curr) => (curr === msg ? null : curr));
    }, 2200);
  }, []);

  const copyText = useCallback(
    (text: string, label = 'Copied to clipboard') => {
      if (navigator.clipboard) {
        navigator.clipboard.writeText(text).catch(() => {});
      }
      showToast(label);
    },
    [showToast]
  );

  const navigate = useCallback(
    (next: AppRoute) => {
      setPreviousRoute(route);
      setRoute(next);
    },
    [route]
  );

  const goBack = useCallback(() => {
    if (route === 'traffic-detail') {
      setRoute('traffic');
    } else if (route === 'https-ca') {
      setRoute('settings');
    } else {
      setRoute(previousRoute !== route ? previousRoute : 'traffic');
    }
  }, [route, previousRoute]);

  const selectTransaction = useCallback((id: string, openDetail = true) => {
    setSelectedTransactionId(id);
    if (openDetail) {
      setPreviousRoute('traffic');
      setRoute('traffic-detail');
    }
  }, []);

  const clearTransactions = useCallback(() => {
    setTransactions([]);
    setPacketCounterOffset(0);
    setBaseVolumeBytes(0);
    showToast('Traffic stream cleared');
  }, [showToast]);

  const deleteTransaction = useCallback(
    (id: string) => {
      setTransactions((prev) => {
        const remaining = prev.filter((t) => t.id !== id);
        if (selectedTransactionId === id && remaining.length > 0) {
          setSelectedTransactionId(remaining[0].id);
        }
        return remaining;
      });
      if (route === 'traffic-detail') {
        setRoute('traffic');
      }
      showToast('Packet removed from history');
    },
    [route, selectedTransactionId, showToast]
  );

  const selectedTransaction = useMemo(
    () => transactions.find((t) => t.id === selectedTransactionId) ?? transactions[0] ?? null,
    [transactions, selectedTransactionId]
  );

  const totalPacketsCount = transactions.length + packetCounterOffset;

  const totalVolumeLabel = useMemo(() => {
    const sumBytes =
      baseVolumeBytes + transactions.reduce((acc, item) => acc + item.sizeBytes, 0);
    if (sumBytes === 0) return '0 B';
    if (sumBytes < 1024 * 1024) return `${(sumBytes / 1024).toFixed(1)} KB`;
    return `${(sumBytes / (1024 * 1024)).toFixed(1)} MB`;
  }, [baseVolumeBytes, transactions]);

  const uniqueHostsCount = useMemo(() => {
    if (transactions.length === 0) return 0;
    const hosts = new Set(transactions.map((t) => t.host));
    return Math.max(hosts.size, packetCounterOffset > 0 ? 3 : hosts.size);
  }, [transactions, packetCounterOffset]);

  // Intercept actions
  const updateInterceptedBody = useCallback((id: string, body: string) => {
    setInterceptQueue((prev) => prev.map((item) => (item.id === id ? { ...item, body } : item)));
  }, []);

  const updateInterceptedHeader = useCallback((id: string, index: number, value: string) => {
    setInterceptQueue((prev) =>
      prev.map((item) => {
        if (item.id !== id) return item;
        const headers = item.headers.map((h, i) => (i === index ? { ...h, value } : h));
        return { ...item, headers };
      })
    );
  }, []);

  const forwardIntercepted = useCallback(
    (id: string) => {
      const target = interceptQueue.find((q) => q.id === id);
      if (!target) return;

      const newTx: HttpTransaction = {
        id: `tx-fwd-${Date.now()}`,
        sequenceNumber: target.sequenceNumber,
        method: target.method,
        host: target.host,
        path: target.path,
        protocol: target.protocol,
        client: 'Android 14',
        status: 200,
        statusText: 'OK',
        sizeBytes: Math.max(240, target.body.length + 180),
        sizeLabel: formatSize(Math.max(240, target.body.length + 180)),
        durationMs: 118,
        timestamp: nowTimeStr(),
        requestHeaders: target.headers,
        requestParams: target.params,
        requestCookies: target.cookies,
        requestBody: target.body,
        requestContentType: target.contentType,
        responseHeaders: [
          { name: 'HTTP/1.1', value: '200 OK', category: 'status' },
          { name: 'Content-Type', value: 'application/json; charset=utf-8', category: 'header' },
          { name: 'X-Intercepted', value: 'forwarded-by-mobileburp', category: 'trace' },
        ],
        responseBody: JSON.stringify(
          {
            status: 'forwarded',
            sequence: target.sequenceNumber,
            authenticated: true,
          },
          null,
          2
        ),
        responseContentType: 'application/json',
      };

      if (settings.captureTraffic) {
        setTransactions((prev) => [newTx, ...prev].slice(0, settings.maxHistory));
      }
      setInterceptQueue((prev) => prev.filter((q) => q.id !== id));
      showToast(`Forwarded #${String(target.sequenceNumber).padStart(3, '0')} → 200 OK`);
    },
    [interceptQueue, settings.captureTraffic, settings.maxHistory, showToast]
  );

  const dropIntercepted = useCallback(
    (id: string) => {
      const target = interceptQueue.find((q) => q.id === id);
      setInterceptQueue((prev) => prev.filter((q) => q.id !== id));
      if (target) {
        showToast(`Dropped request #${String(target.sequenceNumber).padStart(3, '0')}`);
      }
    },
    [interceptQueue, showToast]
  );

  const simulateIntercepted = useCallback(() => {
    const seq = nextSeq;
    setNextSeq((s) => s + 1);
    const req: InterceptedRequest = {
      id: `int-${seq}-${Date.now()}`,
      sequenceNumber: seq,
      method: 'POST',
      host: 'example.com',
      path: '/api/checkout/confirm',
      protocol: 'TLS 1.3',
      sizeLabel: '216 B',
      timestamp: nowTimeStr(),
      contentType: 'application/json',
      headers: [
        { name: 'Host', value: 'example.com', category: 'Authority' },
        { name: 'Content-Type', value: 'application/json; charset=utf-8', category: 'Media' },
        { name: 'User-Agent', value: `${settings.userAgent} (Android 14)`, category: 'Client' },
        { name: 'Authorization', value: 'Bearer sess_9fa8d011f0c4be', category: 'Bearer' },
      ],
      params: [],
      cookies: [{ name: 'session_token', value: 'sec_abc123490f82' }],
      body: JSON.stringify(
        {
          cart_id: 'crt_88210f',
          promo_code: 'SPRING_AUDIT',
          total_usd: 174.0,
        },
        null,
        2
      ),
    };
    setInterceptQueue((prev) => [...prev, req]);
    showToast(`Paused #${String(seq).padStart(3, '0')} POST /api/checkout/confirm`);
  }, [nextSeq, settings.userAgent, showToast]);

  // Repeater actions
  const sendToRepeater = useCallback(
    (tx: HttpTransaction) => {
      const existing = repeaterTabs.find((t) => t.sourceSequence === tx.sequenceNumber);
      if (existing) {
        setActiveRepeaterTabId(existing.id);
        setRoute('repeater');
        showToast(`Opened ${existing.label} (Request #${String(tx.sequenceNumber).padStart(3, '0')})`);
        return;
      }

      const newIndex = repeaterTabs.length + 1;
      const newTab: RepeaterTab = {
        id: `rep-tab-${Date.now()}`,
        label: `Tab ${newIndex}`,
        sourceSequence: tx.sequenceNumber,
        method: tx.method,
        url: `https://${tx.host}${tx.path}`,
        host: `${tx.host}:443`,
        path: tx.path,
        headers: [...tx.requestHeaders],
        params: [...tx.requestParams],
        cookies: [...tx.requestCookies],
        body: tx.requestBody || '',
        sentCount: 1,
        lastStatus: tx.status,
        lastStatusText: tx.statusText,
        lastDurationMs: tx.durationMs,
        lastSizeBytes: tx.sizeBytes,
        lastSizeLabel: tx.sizeLabel,
        responseHeaders: [...tx.responseHeaders],
        responseBody: tx.responseBody || '',
        history: [
          {
            id: `h-${Date.now()}`,
            timestamp: tx.timestamp,
            method: tx.method,
            url: `https://${tx.host}${tx.path}`,
            status: tx.status,
            statusText: tx.statusText,
            durationMs: tx.durationMs,
            sizeBytes: tx.sizeBytes,
          },
        ],
      };

      setRepeaterTabs((prev) => [...prev, newTab]);
      setActiveRepeaterTabId(newTab.id);
      setRoute('repeater');
      showToast(`Sent #${String(tx.sequenceNumber).padStart(3, '0')} to Repeater (${newTab.label})`);
    },
    [repeaterTabs, showToast]
  );

  const updateRepeaterTab = useCallback((id: string, patch: Partial<RepeaterTab>) => {
    setRepeaterTabs((prev) =>
      prev.map((tab) => {
        if (tab.id !== id) return tab;
        const updated = { ...tab, ...patch };
        if (patch.url) {
          try {
            const parsed = new URL(patch.url.startsWith('http') ? patch.url : `https://${patch.url}`);
            updated.host = `${parsed.hostname}:${parsed.port || '443'}`;
            updated.path = parsed.pathname + parsed.search;
          } catch {
            // keep existing host/path while typing
          }
        }
        return updated;
      })
    );
  }, []);

  const dispatchRepeaterRequest = useCallback(
    (id: string) => {
      const tab = repeaterTabs.find((t) => t.id === id);
      if (!tab) return;

      const latency = Math.floor(85 + Math.random() * 140);
      const status = tab.path.includes('missing') ? 404 : 200;
      const statusText = status === 404 ? 'Not Found' : 'OK';
      const respBody = JSON.stringify(
        {
          status: status === 200 ? 'authenticated' : 'not_found',
          replay_attempt: tab.sentCount + 1,
          session_token: 'sess_9fa8d011f0c4be',
          issued_at: Math.floor(Date.now() / 1000),
          role: 'telemetry_auditor',
          echo_method: tab.method,
          echo_path: tab.path,
          features: ['full_trace', 'packet_replay'],
        },
        null,
        2
      );
      const respBytes = respBody.length + 340;
      const time = nowTimeStr();

      setRepeaterTabs((prev) =>
        prev.map((t) => {
          if (t.id !== id) return t;
          return {
            ...t,
            sentCount: t.sentCount + 1,
            lastStatus: status,
            lastStatusText: statusText,
            lastDurationMs: latency,
            lastSizeBytes: respBytes,
            lastSizeLabel: formatSize(respBytes),
            responseBody: respBody,
            history: [
              {
                id: `h-${Date.now()}`,
                timestamp: time,
                method: t.method,
                url: t.url,
                status,
                statusText,
                durationMs: latency,
                sizeBytes: respBytes,
              },
              ...t.history,
            ],
          };
        })
      );

      if (settings.captureTraffic && settings.proxyEnabled) {
        const seq = nextSeq;
        setNextSeq((s) => s + 1);
        const hostOnly = tab.host.split(':')[0] || 'example.com';
        const tx: HttpTransaction = {
          id: `tx-rep-${Date.now()}`,
          sequenceNumber: seq,
          method: tab.method,
          host: hostOnly,
          path: tab.path,
          protocol: 'TLS 1.3',
          client: 'Repeater/2.1',
          status,
          statusText,
          sizeBytes: respBytes,
          sizeLabel: formatSize(respBytes),
          durationMs: latency,
          timestamp: time,
          requestHeaders: tab.headers,
          requestParams: tab.params,
          requestCookies: tab.cookies,
          requestBody: tab.body,
          requestContentType: 'application/json',
          responseHeaders: tab.responseHeaders,
          responseBody: respBody,
          responseContentType: 'application/json',
        };
        setTransactions((prev) => [tx, ...prev].slice(0, settings.maxHistory));
      }
    },
    [nextSeq, repeaterTabs, settings.captureTraffic, settings.maxHistory, settings.proxyEnabled]
  );

  const duplicateRepeaterTab = useCallback(
    (id: string) => {
      const source = repeaterTabs.find((t) => t.id === id);
      if (!source) return;
      const copy: RepeaterTab = {
        ...source,
        id: `rep-tab-${Date.now()}`,
        label: `Tab ${repeaterTabs.length + 1}`,
        sentCount: 0,
        headers: [...source.headers],
        params: [...source.params],
        cookies: [...source.cookies],
        history: [],
      };
      setRepeaterTabs((prev) => [...prev, copy]);
      setActiveRepeaterTabId(copy.id);
      showToast(`Duplicated into ${copy.label}`);
    },
    [repeaterTabs, showToast]
  );

  const closeRepeaterTab = useCallback(
    (id: string) => {
      if (repeaterTabs.length <= 1) return;
      const filtered = repeaterTabs.filter((t) => t.id !== id);
      setRepeaterTabs(filtered);
      if (activeRepeaterTabId === id) {
        setActiveRepeaterTabId(filtered[0].id);
      }
    },
    [activeRepeaterTabId, repeaterTabs]
  );

  const activeRepeaterTab = useMemo(
    () => repeaterTabs.find((t) => t.id === activeRepeaterTabId) ?? repeaterTabs[0],
    [repeaterTabs, activeRepeaterTabId]
  );

  // Browser simulation
  const emitBrowserRequest = useCallback(
    ({
      method,
      host,
      path,
      body = '',
      status = 200,
      statusText = 'OK',
      responseBody = '',
      addToCartDelta = 0,
    }: {
      method: HttpMethod;
      host: string;
      path: string;
      body?: string;
      status?: number;
      statusText?: string;
      responseBody?: string;
      addToCartDelta?: number;
    }) => {
      if (addToCartDelta !== 0) {
        setCartCount((c) => Math.max(0, c + addToCartDelta));
      }

      if (!settings.proxyEnabled || !settings.captureTraffic) {
        showToast('Proxy capture disabled — request bypassed');
        return;
      }

      const seq = nextSeq;
      setNextSeq((s) => s + 1);
      const time = nowTimeStr();
      const headers: HttpHeader[] = [
        { name: 'Host', value: host, category: 'standard' },
        { name: 'User-Agent', value: `${settings.userAgent} (Android 14; Pixel 8 Pro)`, category: 'client' },
        { name: 'Accept', value: 'application/json, text/html, */*', category: 'mime' },
        { name: 'Cookie', value: `cart_count=${cartCount + addToCartDelta}; theme=dark`, category: 'storage' },
      ];
      if (body) {
        headers.splice(1, 0, {
          name: 'Content-Type',
          value: 'application/json; charset=utf-8',
          category: 'header',
        });
      }

      const bytes = Math.max(480, responseBody.length + body.length + 310);
      const newTx: HttpTransaction = {
        id: `tx-brw-${Date.now()}-${seq}`,
        sequenceNumber: seq,
        method,
        host,
        path,
        protocol: 'TLS 1.3',
        client: 'Android 14',
        status,
        statusText,
        sizeBytes: bytes,
        sizeLabel: formatSize(bytes),
        durationMs: Math.floor(45 + Math.random() * 130),
        timestamp: time,
        requestHeaders: headers,
        requestParams: [],
        requestCookies: [{ name: 'cart_count', value: String(cartCount + addToCartDelta) }],
        requestBody: body,
        requestContentType: body ? 'application/json' : undefined,
        responseHeaders: [
          { name: 'HTTP/1.1', value: `${status} ${statusText}`, category: 'status' },
          { name: 'Content-Type', value: 'application/json; charset=utf-8', category: 'header' },
        ],
        responseBody:
          responseBody ||
          JSON.stringify({ ok: true, path, timestamp: time, cartCount: cartCount + addToCartDelta }, null, 2),
        responseContentType: 'application/json',
      };

      setTransactions((prev) => [newTx, ...prev].slice(0, settings.maxHistory));
      showToast(`Captured ${method} ${path} (${status})`);
    },
    [
      cartCount,
      nextSeq,
      settings.captureTraffic,
      settings.maxHistory,
      settings.proxyEnabled,
      settings.userAgent,
      showToast,
    ]
  );

  const updateSetting = useCallback(
    <K extends keyof ProxySettings>(key: K, value: ProxySettings[K]) => {
      setSettings((prev) => ({ ...prev, [key]: value }));
    },
    []
  );

  const installToSystemStore = useCallback(() => {
    setCaDetails((prev) => ({
      ...prev,
      installedToSystemStore: true,
      trustStatus: 'System + User Trusted',
    }));
    showToast('CA certificate staged to /system/etc/security/cacerts');
  }, [showToast]);

  const regenerateCa = useCallback(() => {
    setCaDetails((prev) => ({
      ...prev,
      sha256: randomHexSegment(32),
      sha1: randomHexSegment(20),
      serialNumber: randomHexSegment(8),
      notBefore: `${new Date().toISOString().slice(0, 10)} 00:00:00 UTC`,
    }));
    showToast('Generated fresh RSA-2048 Development CA keypair');
  }, [showToast]);

  return (
    <ProxyContext.Provider
      value={{
        route,
        navigate,
        goBack,
        transactions,
        selectedTransaction,
        selectTransaction,
        clearTransactions,
        deleteTransaction,
        totalPacketsCount,
        totalVolumeLabel,
        uniqueHostsCount,
        interceptQueue,
        updateInterceptedBody,
        updateInterceptedHeader,
        forwardIntercepted,
        dropIntercepted,
        simulateIntercepted,
        repeaterTabs,
        activeRepeaterTab,
        setActiveRepeaterTabId,
        sendToRepeater,
        updateRepeaterTab,
        dispatchRepeaterRequest,
        duplicateRepeaterTab,
        closeRepeaterTab,
        browserUrl,
        setBrowserUrl,
        cartCount,
        emitBrowserRequest,
        settings,
        updateSetting,
        caDetails,
        installToSystemStore,
        regenerateCa,
        toast,
        showToast,
        copyText,
      }}
    >
      {children}
    </ProxyContext.Provider>
  );
};

export function useProxy(): ProxyContextValue {
  const ctx = useContext(ProxyContext);
  if (!ctx) throw new Error('useProxy must be used within ProxyProvider');
  return ctx;
}
