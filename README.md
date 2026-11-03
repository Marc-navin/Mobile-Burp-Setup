# Mobile Burp Suite — Android HTTP/HTTPS Interception Proxy

**Mobile Burp Suite** is an Android-based browser and security testing tool designed to capture, inspect, and manipulate HTTP/HTTPS traffic directly from a mobile device.

The project combines a WebView-based browser with a local interception proxy, TLS interception, HTTP history, request modification, and security testing utilities.

> Built as a mobile-focused alternative for inspecting and testing web application traffic.

---

## Features

### 🌐 Browser
- WebView-based Android browser.
- URL navigation and search.
- Back, forward, and reload controls.
- HTTP and HTTPS browsing.
- Browser traffic routed through the local proxy.

### 🔍 HTTP/HTTPS Proxy
- Local HTTP proxy server.
- HTTP request and response forwarding.
- HTTPS CONNECT tunneling.
- TLS Man-in-the-Middle (MITM) interception.
- Per-host dynamically generated certificates.
- Upstream certificate-chain and hostname verification.
- HTTP/1.1 traffic handling.

### 🔐 Certificate Authority Management
- Dynamically generated development Certificate Authority.
- RSA 2048-bit CA key.
- Per-host certificates signed by the development CA.
- Public CA certificate export.
- SHA-256 certificate fingerprint display.
- CA identity and export-status verification.
- Manual CA installation through Android settings.

### 📜 HTTP History
- Captured HTTP request and response history.
- Request and response body inspection.
- Bounded history storage.
- Configurable capture limits.
- FIFO history eviction.
- History clearing.

### ✋ Intercept
- Pause intercepted requests.
- Inspect and modify requests.
- Forward or drop requests.
- Automatic timeout handling.
- Bounded interception queue.

### 🔁 Repeater
- Manually construct and modify HTTP requests.
- Send requests directly to target servers.
- Inspect captured responses.
- Save requests for repeated testing.

### ⚡ Variation Lab
- Sequential request variation testing.
- Custom payload markers.
- URL, header, and body parameter variation.
- Configurable request delays.
- Pause, resume, and stop execution.
- Bounded execution and result retention.

---

## Architecture

Mobile Burp Suite uses a local proxy server to route browser traffic through its interception pipeline.

```text
              Android Browser (WebView)
                         |
                         |
                  Local Proxy Server
                   127.0.0.1:8080
                         |
               +---------+---------+
               |                   |
          HTTP Handler        HTTPS CONNECT
               |                   |
         HTTP Forwarding      TLS Interceptor
                                   |
                           Development CA
                                   |
                         Per-Host Certificates
                                   |
                         Upstream TLS Connection
                                   |
                              Web Server
```

Additional components:

- **HTTP History Repository:** Stores captured requests and responses.
- **Intercept Manager:** Manages paused requests and forwarding decisions.
- **Repeater:** Handles manually constructed HTTP requests.
- **Variation Lab:** Generates and executes sequential request variations.
- **Certificate Authority:** Manages the development CA and host certificates.

---

## Technology Stack

| Technology | Purpose |
|---|---|
| Kotlin | Main application language |
| Jetpack Compose | Android UI |
| Android WebView | Browser engine |
| Java 17 | Development toolchain |
| Bouncy Castle | Certificate generation |
| Gradle | Build system |
| Android SDK | Android application platform |

---

## Requirements

- Android Studio.
- Android SDK.
- JDK 17.
- Gradle wrapper included in the repository.
- Android device or emulator.
- Android 7.0 (API 24) or later.

---
---
### If you are using the Android phone, you can download the latest version from the releases
---
## Installation & Setup for PCs

### 1. Clone the repository

```bash
git clone https://github.com/Marc-navin/Android-Burp-Suite.git
```

### 2. Open the project

Open the cloned project directory in Android Studio.

Allow Gradle to synchronize the project dependencies.

### 3. Build the application

On Windows:

```powershell
.\gradlew.bat assembleDebug
```

On Linux/macOS:

```bash
./gradlew assembleDebug
```

### 4. Install the application

```powershell
.\gradlew.bat installDebug
```

Alternatively, install the generated APK manually:

```text
app/build/outputs/apk/debug/app-debug.apk
```

---

## HTTPS Interception Setup

HTTPS interception requires installing the development CA certificate on the Android device.

1. Launch Mobile Burp Suite.
2. Open the CA Management section.
3. Export the public CA certificate.
4. Open Android Settings.
5. Navigate to Security / Encryption & credentials.
6. Install the exported certificate as a CA certificate under User Credentials.
7. Return to Mobile Burp Suite.
8. Browse an HTTPS website through the local proxy.

**Important:**
- The app cannot independently verify whether Android has installed the CA certificate.
- Installing a user CA changes the device's trust configuration.
- Remove the development CA when it is no longer required.
- Only install certificates you generated and trust.

---

## 🚀 First-Time Setup — What To Do After Installing

Follow these steps to get BasicBrowser ready for HTTP/HTTPS traffic inspection.

### Step 1 — Launch BasicBrowser

- Open BasicBrowser after installation.
- Allow any required Android permissions when prompted.
- Navigate to the Browser section.

### Step 2 — Start the Local Proxy

- Ensure the local proxy server is running.
- BasicBrowser uses `127.0.0.1:8080` for its local proxy.
- Keep the application running while using the browser.

### Step 3 — Set Up the Development CA Certificate

To inspect HTTPS traffic, you must install BasicBrowser's development CA certificate.

1. Open the **CA Management** section.
2. Generate or initialize the development CA if it has not already been created.
3. Export the public CA certificate.
4. Open Android Settings.
5. Navigate to **Security → Encryption & credentials → Install a certificate → CA certificate**. The exact menu names may differ depending on your Android version.
6. Select the exported BasicBrowser CA certificate.
7. Follow Android's instructions to complete installation.

**Important:** Installing a CA certificate allows the device to trust certificates signed by that CA. Only install certificates you trust.

### Step 4 — Verify HTTPS Interception

- Return to BasicBrowser.
- Open the Browser section.
- Visit an HTTPS website.
- Check whether the HTTPS request and response appear in HTTP History.

If HTTPS interception does not work, verify that the correct CA certificate is installed and that its fingerprint matches the active certificate shown in CA Management.

### Step 5 — Explore the Main Features

| Feature | Purpose |
|---|---|
| Browser | Browse websites through the local proxy |
| HTTP History | Inspect captured requests and responses |
| Intercept | Pause, inspect, modify, forward, or drop requests |
| Repeater | Manually send and modify HTTP requests |
| Variation Lab | Perform controlled request variation testing |

### ⚠️ Important Security Notes

- HTTPS interception requires the development CA to be installed.
- BasicBrowser cannot independently verify Android's user-installed CA status.
- Some applications use certificate pinning and may reject interception.
- HTTP traffic is not encrypted.
- Remove the development CA from Android when you no longer need it.
- Use the tool only on systems and applications you own or are authorized to test.

---
## Security Considerations

Mobile Burp Suite is intended for authorized security testing and educational purposes.

- Use only against applications and systems you own or have explicit permission to test.
- The development CA private key must remain protected in application-private storage.
- Never share the private CA key or keystore password.
- HTTPS interception requires explicit installation of the development CA.
- Upstream TLS certificate validation and hostname verification must remain enabled.
- HTTP traffic is inherently unencrypted.
- Certificate pinning may prevent interception of certain applications or services.

---

## Testing

The project has undergone multiple rounds of development and verification, including:

- Unit testing.
- HTTP and HTTPS proxy testing.
- TLS interception and certificate validation testing.
- Concurrent connection testing.
- Interception queue testing.
- Request forwarding and response capture testing.
- History eviction and memory stress testing.
- Debug and Release APK build verification.
- Physical Android device testing.

**Latest reported test results:**
- 144 unit tests passed.
- Zero reported test failures.
- Debug and Release builds successful.
- Physical-device HTTP/HTTPS verification.
- 250-request controlled history memory stress test without reported crashes or OOM errors.

These results describe the tested development snapshot and are not a formal security certification.

---

## Project Status

**Mobile Burp Suite v1.0 Release Candidate**

The core browser, proxy, TLS interception, HTTP history, Intercept, Repeater, and Variation Lab functionality has undergone testing on physical Android hardware.

Further improvements and refinements may be introduced as development continues.

---

## Disclaimer

Mobile Burp Suite is an educational and security-testing project.

The developer is not responsible for unauthorized use, misuse, or damage caused by this software.

Always obtain appropriate authorization before performing security testing.

---

## Author

**Navin S**

Cybersecurity Enthusiast | Android Security Tool Development

GitHub: [@Marc-navin](https://github.com/Marc-navin)

---

*Built to bring HTTP/HTTPS traffic inspection and web security testing closer to Android devices.*
