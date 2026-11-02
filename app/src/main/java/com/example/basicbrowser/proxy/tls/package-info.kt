/**
 * # proxy.tls — TLS Interception Layer
 *
 * This package contains the modular components for HTTPS interception
 * in the BasicBrowser local proxy. The architecture is designed to be
 * built incrementally:
 *
 * ## Components
 *
 * ### CertificateAuthority (IMPLEMENTED)
 * Manages a development-only root CA (keypair + self-signed certificate).
 * The CA is generated once and stored in an app-private PKCS12 keystore.
 * It is used exclusively for local development/testing and is never
 * installed into Android's system or user trust store.
 *
 * ### CertificateGenerator (FUTURE)
 * Will generate short-lived, per-host TLS certificates signed by the
 * development CA. These certificates are presented to the WebView
 * during TLS handshake so the proxy can decrypt and inspect HTTPS
 * traffic.
 *
 * ### TlsInterceptor (FUTURE)
 * Will perform the actual TLS man-in-the-middle: terminate the client
 * TLS connection using a per-host certificate, decrypt the HTTP
 * request, forward it to the real server over a new TLS connection,
 * and relay the response back.
 *
 * ### ConnectHandler (FUTURE)
 * Will handle HTTP CONNECT requests, which is how browsers signal
 * that they want to establish a TLS tunnel through a proxy. The
 * ConnectHandler will respond with 200 OK and then hand off to
 * TlsInterceptor.
 *
 * ## Data Flow (Future)
 *
 * ```
 * Browser (WebView)
 *   → ProxyController (ALL traffic)
 *   → ProxyServer (accept loop)
 *   → ConnectHandler (CONNECT tunnel)
 *   → TlsInterceptor (MITM with per-host certs)
 *   → HttpProxyHandler (parse / forward / relay)
 *   → History / Intercept / Repeater / Intruder
 * ```
 *
 * ## Security Notes
 *
 * - The development CA private key is stored in an app-private PKCS12
 *   keystore and is never exposed via public API, logs, or UI.
 * - The CA is NOT installed into Android's system or user certificate
 *   store. It only affects connections terminated by this proxy.
 * - No attempt is made to bypass certificate pinning or other
 *   application security controls.
 */
package com.example.basicbrowser.proxy.tls
