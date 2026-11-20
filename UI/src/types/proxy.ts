export type HttpMethod = 'GET' | 'POST' | 'PUT' | 'DELETE' | 'PATCH';

export type AppRoute =
  | 'browser'
  | 'traffic'
  | 'traffic-detail'
  | 'intercept'
  | 'repeater'
  | 'settings'
  | 'https-ca';

export interface HttpHeader {
  name: string;
  value: string;
  category?: string;
}

export interface HttpParam {
  key: string;
  value: string;
}

export interface HttpCookie {
  name: string;
  value: string;
  attributes?: string;
}

export interface HttpTransaction {
  id: string;
  sequenceNumber: number; // e.g., 42 -> #042
  method: HttpMethod;
  host: string;
  path: string;
  protocol: string; // e.g., "TLS 1.3"
  client: string; // e.g., "Android 14"
  status: number;
  statusText: string;
  sizeBytes: number;
  sizeLabel: string;
  durationMs: number;
  timestamp: string;
  requestHeaders: HttpHeader[];
  requestParams: HttpParam[];
  requestCookies: HttpCookie[];
  requestBody: string;
  requestContentType?: string;
  responseHeaders: HttpHeader[];
  responseBody: string;
  responseContentType?: string;
}

export interface InterceptedRequest {
  id: string;
  sequenceNumber: number;
  method: HttpMethod;
  host: string;
  path: string;
  protocol: string;
  sizeLabel: string;
  timestamp: string;
  headers: HttpHeader[];
  params: HttpParam[];
  cookies: HttpCookie[];
  body: string;
  contentType: string;
}

export interface RepeaterHistoryEntry {
  id: string;
  timestamp: string;
  method: HttpMethod;
  url: string;
  status: number;
  statusText: string;
  durationMs: number;
  sizeBytes: number;
}

export interface RepeaterTab {
  id: string;
  label: string;
  sourceSequence?: number;
  method: HttpMethod;
  url: string;
  host: string;
  path: string;
  headers: HttpHeader[];
  params: HttpParam[];
  cookies: HttpCookie[];
  body: string;
  sentCount: number;
  lastStatus: number;
  lastStatusText: string;
  lastDurationMs: number;
  lastSizeBytes: number;
  lastSizeLabel: string;
  responseHeaders: HttpHeader[];
  responseBody: string;
  history: RepeaterHistoryEntry[];
}

export interface ProxySettings {
  proxyEnabled: boolean;
  port: number;
  proxyMode: 'Local (127.0.0.1)' | 'All Interfaces (0.0.0.0)';
  httpsInterception: boolean;
  captureTraffic: boolean;
  maxHistory: number;
  userAgent: string;
  jsEnabled: boolean;
  compactMode: boolean;
  syntaxTheme: string;
  interceptEnabled: boolean;
}

export interface CaDetails {
  commonName: string;
  standard: string;
  keyAlgorithm: string;
  issuerOrg: string;
  subjectName: string;
  trustStatus: string;
  installedToUserStore: boolean;
  installedToSystemStore: boolean;
  notBefore: string;
  notAfter: string;
  daysRemaining: number;
  sha256: string;
  sha1: string;
  serialNumber: string;
  signatureAlgorithm: string;
  publicKeySpec: string;
  basicConstraints: string;
  keyUsage: string;
}

export interface StoreProduct {
  id: string;
  sku: string;
  tag?: string;
  tagColor?: 'slate' | 'emerald';
  name: string;
  subtitle: string;
  price: number;
  icon: 'keyboard' | 'key' | 'headset' | 'mouse';
}
