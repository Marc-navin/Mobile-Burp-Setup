import React, { useState } from 'react';
import {
  ArrowLeft,
  ArrowRight,
  Headphones,
  Key,
  Lock,
  Monitor,
  MoreVertical,
  MousePointer,
  RotateCw,
  Search,
  ShoppingBag,
  X,
  Zap,
} from 'lucide-react';
import { useProxy } from '../context/ProxyContext';
import { STORE_PRODUCTS } from '../data/mockTraffic';
import { StoreProduct } from '../types/proxy';

export const BrowserView: React.FC = () => {
  const {
    browserUrl,
    setBrowserUrl,
    cartCount,
    emitBrowserRequest,
    totalPacketsCount,
    totalVolumeLabel,
    transactions,
    selectTransaction,
    navigate,
    settings,
    showToast,
  } = useProxy();

  const [urlInput, setUrlInput] = useState(browserUrl);
  const [isEditingUrl, setIsEditingUrl] = useState(false);
  const [searchOpen, setSearchOpen] = useState(false);
  const [storeSearch, setStoreSearch] = useState('');
  const [addedIds, setAddedIds] = useState<Record<string, boolean>>({});
  const [historyStack, setHistoryStack] = useState<string[]>([browserUrl]);
  const [historyIndex, setHistoryIndex] = useState(0);
  const [reloading, setReloading] = useState(false);

  const visibleProducts = STORE_PRODUCTS.filter(
    (p) =>
      !storeSearch.trim() ||
      p.name.toLowerCase().includes(storeSearch.toLowerCase()) ||
      p.subtitle.toLowerCase().includes(storeSearch.toLowerCase())
  );

  const navigateUrl = (target: string) => {
    const clean = target.replace(/^https?:\/\//, '').trim() || 'example.com/store/products';
    const [host, ...rest] = clean.split('/');
    const path = `/${rest.join('/')}`;
    setBrowserUrl(clean);
    setUrlInput(clean);
    setIsEditingUrl(false);

    const nextStack = [...historyStack.slice(0, historyIndex + 1), clean];
    setHistoryStack(nextStack);
    setHistoryIndex(nextStack.length - 1);

    emitBrowserRequest({
      method: 'GET',
      host: host || 'example.com',
      path,
      status: 200,
      statusText: 'OK',
    });
  };

  const handleReload = () => {
    setReloading(true);
    const [host, ...rest] = browserUrl.split('/');
    const path = `/${rest.join('/')}`;
    emitBrowserRequest({
      method: 'GET',
      host: host || 'example.com',
      path,
      status: 200,
      statusText: 'OK',
    });
    window.setTimeout(() => setReloading(false), 350);
  };

  const handleAddToCart = (product: StoreProduct) => {
    setAddedIds((prev) => ({ ...prev, [product.id]: true }));
    emitBrowserRequest({
      method: 'POST',
      host: 'example.com',
      path: '/api/cart/add',
      body: JSON.stringify({ sku: product.sku, qty: 1, price: product.price }, null, 2),
      status: 200,
      statusText: 'OK',
      addToCartDelta: 1,
      responseBody: JSON.stringify(
        { added: true, sku: product.sku, cartCount: cartCount + 1 },
        null,
        2
      ),
    });
    window.setTimeout(() => {
      setAddedIds((prev) => ({ ...prev, [product.id]: false }));
    }, 900);
  };

  const renderProductIcon = (icon: StoreProduct['icon']) => {
    if (icon === 'keyboard') return <Monitor className="w-10 h-10 text-[#869397]" />;
    if (icon === 'key') return <Key className="w-10 h-10 text-[#869397]" />;
    if (icon === 'headset') return <Headphones className="w-10 h-10 text-[#869397]" />;
    return <MousePointer className="w-10 h-10 text-[#869397]" />;
  };

  return (
    <div className="flex flex-1 min-h-0 w-full bg-[#0b0f14]">
      {/* Primary Embedded Browser Pane */}
      <div className="flex-1 flex flex-col min-h-0 min-w-0 lg:border-r lg:border-[#233142]">
        {/* In-App Browser URL Bar */}
        <div className="px-3 py-2 bg-[#101721] border-b border-[#233142] flex items-center gap-2 shrink-0">
          {isEditingUrl ? (
            <form
              onSubmit={(e) => {
                e.preventDefault();
                navigateUrl(urlInput);
              }}
              className="flex-1 flex items-center bg-[#16202e] border border-[#06b6d4] rounded-lg px-2.5 py-1.5 text-xs font-mono"
            >
              <Lock className="w-3.5 h-3.5 text-[#10b981] mr-2 shrink-0" />
              <input
                type="text"
                autoFocus
                value={urlInput}
                onChange={(e) => setUrlInput(e.target.value)}
                onBlur={() => setIsEditingUrl(false)}
                className="flex-1 bg-transparent text-white focus:outline-none text-xs"
                aria-label="Browser URL"
              />
            </form>
          ) : (
            <button
              type="button"
              onClick={() => {
                setUrlInput(browserUrl);
                setIsEditingUrl(true);
              }}
              className="flex-1 flex items-center bg-[#16202e] border border-[#233142] hover:border-[#06b6d4]/60 transition-colors rounded-lg px-2.5 py-1.5 text-xs font-mono shadow-sm text-left cursor-text"
            >
              <Lock className="w-3.5 h-3.5 text-[#10b981] mr-1.5 shrink-0" />
              <div className="flex-1 truncate">
                <span className="text-white font-medium">
                  {browserUrl.split('/')[0]}
                </span>
                <span className="text-[#869397]">
                  /{browserUrl.split('/').slice(1).join('/')}
                </span>
              </div>
              <span className="text-[9px] font-mono px-1.5 py-0.5 rounded bg-[#0b0f14] text-[#bcc9cd] border border-[#262a30] ml-2 shrink-0">
                TLS 1.3
              </span>
            </button>
          )}
        </div>

        {/* Embedded Storefront WebView */}
        <div className="flex-1 overflow-y-auto bg-[#0d1117] relative flex flex-col text-slate-200 min-h-0">
          {/* Storefront Header */}
          <div className="w-full bg-[#161b22] border-b border-slate-800 px-4 py-3 flex items-center justify-between sticky top-0 z-10">
            <div className="flex items-center gap-2">
              <div className="w-6 h-6 rounded bg-gradient-to-tr from-cyan-500 to-indigo-600 flex items-center justify-center font-bold text-xs text-white">
                E
              </div>
              <span className="text-sm font-semibold tracking-tight text-white">
                Example Store
              </span>
            </div>
            <div className="flex items-center gap-3">
              <button
                type="button"
                onClick={() => setSearchOpen((v) => !v)}
                aria-label="Search Store"
                className="text-slate-400 hover:text-white cursor-pointer p-1"
              >
                {searchOpen ? <X className="w-4 h-4" /> : <Search className="w-4 h-4" />}
              </button>
              <button
                type="button"
                onClick={() => {
                  emitBrowserRequest({
                    method: 'GET',
                    host: 'example.com',
                    path: '/api/cart',
                    status: 200,
                    statusText: 'OK',
                    responseBody: JSON.stringify({ items: cartCount, currency: 'USD' }, null, 2),
                  });
                }}
                aria-label="Shopping Bag"
                className="relative cursor-pointer p-1"
              >
                <ShoppingBag className="w-4 h-4 text-slate-300" />
                <span className="absolute -top-0.5 -right-1 bg-indigo-500 text-white font-mono text-[9px] px-1 rounded-full font-bold tabular-nums">
                  {cartCount}
                </span>
              </button>
            </div>
          </div>

          {searchOpen && (
            <div className="px-3 pt-2.5">
              <div className="flex items-center bg-[#161b22] border border-slate-700 rounded-lg px-3 py-1.5">
                <Search className="w-3.5 h-3.5 text-slate-400 mr-2" />
                <input
                  type="text"
                  value={storeSearch}
                  onChange={(e) => setStoreSearch(e.target.value)}
                  onKeyDown={(e) => {
                    if (e.key === 'Enter' && storeSearch.trim()) {
                      emitBrowserRequest({
                        method: 'GET',
                        host: 'example.com',
                        path: `/api/search?q=${encodeURIComponent(storeSearch.trim())}`,
                      });
                    }
                  }}
                  placeholder="Search hardware catalog..."
                  className="flex-1 bg-transparent text-xs text-white focus:outline-none"
                />
              </div>
            </div>
          )}

          {/* Promo Hero Banner */}
          <div className="m-3 p-3.5 rounded-xl bg-gradient-to-r from-slate-900 to-slate-800 border border-slate-800 relative overflow-hidden">
            <div className="absolute -right-4 -top-4 w-24 h-24 bg-cyan-500/10 rounded-full blur-xl pointer-events-none" />
            <div className="relative">
              <span className="text-[10px] font-semibold uppercase tracking-wider text-cyan-400 block mb-1">
                Spring Drop
              </span>
              <h2 className="text-base font-bold text-white tracking-tight">
                Curated Hardware Essentials
              </h2>
              <p className="text-xs text-slate-400 mt-1 mb-2">
                Engineered precision gear designed for modern workstations.
              </p>
              <span className="inline-block text-[11px] font-medium text-slate-200 bg-slate-800 border border-slate-700 px-2 py-0.5 rounded">
                Free shipping on orders over $50
              </span>
            </div>
          </div>

          {/* Product Catalog Grid */}
          <div className="p-3 grid grid-cols-2 sm:grid-cols-2 md:grid-cols-4 lg:grid-cols-2 xl:grid-cols-4 gap-3 pb-16">
            {visibleProducts.map((product) => {
              const justAdded = addedIds[product.id];
              return (
                <article
                  key={product.id}
                  className="bg-[#161b22] border border-slate-800 rounded-lg p-2.5 flex flex-col justify-between hover:border-slate-700 transition-colors"
                >
                  <div>
                    <div className="w-full h-28 bg-[#21262d] rounded-md mb-2 flex items-center justify-center relative overflow-hidden">
                      {renderProductIcon(product.icon)}
                      {product.tag && (
                        <span
                          className={`absolute top-1.5 left-1.5 bg-slate-900/80 text-[9px] font-mono px-1.5 py-0.5 rounded border ${
                            product.tagColor === 'emerald'
                              ? 'text-emerald-400 border-emerald-900/50'
                              : 'text-slate-300 border-slate-700'
                          }`}
                        >
                          {product.tag}
                        </span>
                      )}
                    </div>
                    <h3 className="text-xs font-semibold text-white leading-tight">
                      {product.name}
                    </h3>
                    <p className="text-[11px] text-slate-400 mt-0.5">{product.subtitle}</p>
                  </div>
                  <div className="mt-2.5 flex items-center justify-between pt-2 border-t border-slate-800">
                    <span className="text-xs font-mono font-bold text-white tabular-nums">
                      ${product.price.toFixed(2)}
                    </span>
                    <button
                      type="button"
                      onClick={() => handleAddToCart(product)}
                      className={`px-2.5 py-1 rounded text-[10px] font-medium transition-colors cursor-pointer ${
                        justAdded
                          ? 'bg-[#00a572]/30 border border-[#4edea3]/50 text-[#4edea3]'
                          : 'bg-slate-800 hover:bg-slate-700 border border-slate-700 text-slate-200'
                      }`}
                    >
                      {justAdded ? 'Added' : 'Add'}
                    </button>
                  </div>
                </article>
              );
            })}
          </div>

          {/* Floating Capture Telemetry Pill */}
          <div className="sticky bottom-3 self-center z-10 pb-2">
            <button
              type="button"
              onClick={() => navigate('traffic')}
              className="flex items-center gap-2 bg-[#16202e]/95 backdrop-blur border border-[#06b6d4]/40 px-3.5 py-1.5 rounded-full text-xs font-mono text-slate-200 shadow-lg hover:border-[#4cd7f6] transition-colors cursor-pointer tabular-nums"
            >
              <span
                className={`w-2 h-2 rounded-full ${
                  settings.proxyEnabled && settings.captureTraffic
                    ? 'bg-[#10b981] animate-pulse'
                    : 'bg-[#869397]'
                }`}
              />
              <span>Capturing traffic</span>
              <span className="text-[#06b6d4] font-medium">({totalPacketsCount} reqs)</span>
              <span className="text-slate-500">|</span>
              <span className="text-slate-300">{totalVolumeLabel}</span>
            </button>
          </div>
        </div>

        {/* Bottom Browser Toolbar */}
        <div className="w-full bg-[#0b0f14] border-t border-[#233142] px-4 py-1.5 flex items-center justify-between shrink-0">
          <button
            type="button"
            disabled={historyIndex <= 0}
            onClick={() => {
              if (historyIndex > 0) {
                const next = historyIndex - 1;
                setHistoryIndex(next);
                setBrowserUrl(historyStack[next]);
              }
            }}
            aria-label="Browser Back"
            className={`p-2 transition-transform ${
              historyIndex > 0
                ? 'text-slate-300 hover:text-white active:scale-95 cursor-pointer'
                : 'text-slate-600 cursor-not-allowed'
            }`}
          >
            <ArrowLeft className="w-4 h-4" />
          </button>

          <button
            type="button"
            disabled={historyIndex >= historyStack.length - 1}
            onClick={() => {
              if (historyIndex < historyStack.length - 1) {
                const next = historyIndex + 1;
                setHistoryIndex(next);
                setBrowserUrl(historyStack[next]);
              }
            }}
            aria-label="Browser Forward"
            className={`p-2 transition-transform ${
              historyIndex < historyStack.length - 1
                ? 'text-slate-300 hover:text-white active:scale-95 cursor-pointer'
                : 'text-slate-600 cursor-not-allowed'
            }`}
          >
            <ArrowRight className="w-4 h-4" />
          </button>

          <button
            type="button"
            onClick={handleReload}
            aria-label="Reload Page"
            className="p-2 text-slate-400 hover:text-[#06b6d4] transition-transform cursor-pointer"
          >
            <RotateCw className={`w-4 h-4 ${reloading ? 'animate-spin text-[#4cd7f6]' : ''}`} />
          </button>

          <button
            type="button"
            onClick={() => navigate('traffic')}
            aria-label="Open Traffic Log"
            className="px-3 py-1.5 rounded-lg bg-[#16202e] border border-[#233142] hover:border-cyan-500/50 flex items-center gap-1.5 active:scale-95 transition-all cursor-pointer tabular-nums"
          >
            <Zap className="w-3.5 h-3.5 text-[#06b6d4]" />
            <span className="text-xs font-mono font-medium text-slate-200">Traffic</span>
            <span className="bg-cyan-500/20 text-[#06b6d4] border border-cyan-500/40 text-[10px] font-mono px-1.5 py-0.5 rounded-full font-semibold">
              {totalPacketsCount}
            </span>
          </button>

          <button
            type="button"
            onClick={() => showToast(`User-Agent: ${settings.userAgent}`)}
            aria-label="Browser Options"
            className="p-2 text-slate-400 hover:text-white cursor-pointer"
          >
            <MoreVertical className="w-4 h-4" />
          </button>
        </div>
      </div>

      {/* Desktop Live Side Stream */}
      <aside className="hidden lg:flex flex-col w-80 xl:w-96 shrink-0 bg-[#101419] min-h-0">
        <div className="px-3 py-2.5 border-b border-[#262a30] flex items-center justify-between">
          <span className="font-mono text-[11px] font-semibold uppercase tracking-wider text-[#4cd7f6]">
            Live WebView Requests
          </span>
          <button
            type="button"
            onClick={() => navigate('traffic')}
            className="font-mono text-[11px] text-[#bcc9cd] hover:text-[#4cd7f6] cursor-pointer"
          >
            View All →
          </button>
        </div>
        <div className="flex-1 overflow-y-auto p-2.5 space-y-2">
          {transactions.slice(0, 8).map((tx) => (
            <button
              key={tx.id}
              type="button"
              onClick={() => {
                selectTransaction(tx.id, false);
                navigate('traffic');
              }}
              className="w-full text-left p-2.5 rounded-lg bg-[#181c21] hover:bg-[#1c2025] transition-colors flex flex-col gap-1 cursor-pointer"
            >
              <div className="flex items-center justify-between">
                <span className="font-mono text-[10px] font-bold text-[#4cd7f6]">{tx.method}</span>
                <span
                  className={`font-mono text-[10px] tabular-nums ${
                    tx.status >= 400 ? 'text-[#ffb4ab]' : 'text-[#4edea3]'
                  }`}
                >
                  {tx.status} {tx.statusText}
                </span>
              </div>
              <span className="font-mono text-[12px] text-[#e0e2ea] truncate">{tx.path}</span>
              <div className="flex items-center justify-between font-mono text-[10px] text-[#869397] tabular-nums">
                <span>{tx.host}</span>
                <span>{tx.durationMs}ms</span>
              </div>
            </button>
          ))}
        </div>
      </aside>
    </div>
  );
};
