# Aether Android — Security Review (v1.2.0)

_Scope: `studio.cluvex.aether` Android app + its use of the native `libaether.so` engine and `libhev-socks5-tunnel.so`. Reviewed as source (no runtime pen-test)._

## Overall score: 82 / 100  (was ~63 before this release)

A solid, privacy-respecting circumvention client. The biggest pre-existing risks (an open LAN proxy and an oversized MTU) are fixed in this release. Remaining points are mostly hardening, not active vulnerabilities.

| Area | Score | Notes |
|------|-------|-------|
| Network exposure / attack surface | 18/20 | Proxy now loopback-only by default |
| Data at rest / secrets | 15/20 | No secrets logged; DataStore unencrypted |
| Transport security | 17/20 | Engine does its own TLS/QUIC; one plain-HTTP geo probe |
| Permissions & OS integration | 17/20 | Minimal permissions; scoped `<queries>` |
| App hardening / build | 15/20 | No obfuscation/cert-pinning on the geo probe |

---

## Fixed in this release

### 1. Open LAN proxy → loopback by default  (was the #1 risk)
`ShareBridge` previously bound the SOCKS5 (`1080`) and HTTP (`8118`) proxies to `0.0.0.0` whenever the tunnel was up, meaning **anyone on the same Wi-Fi/hotspot could route traffic through the user's tunnel** — including while the user had no idea sharing was on.
- Now `ShareBridge.start(localOnly)` binds to `127.0.0.1` unless the user explicitly turns on "Share on this network."
- Proxy mode starts the bridge loopback-only.
- Impact: closes an unauthenticated open-proxy on untrusted networks.

### 2. MTU 8500 → 1280 default
The hardcoded 8500 MTU caused fragmentation/black-holing on many Iranian mobile carriers (a reliability *and* fingerprinting concern — abnormal MTU is a DPI signal). Now defaults to 1280 and is user-tunable (1280–8500), clamped to a sane range in `AetherVpnService.establishTun`.

### 3. Cellular IP leak/confusion fixed
`NetProbe` forced IPv4 resolution for the geo/IP probe, so the "Your IP" badge no longer shows a bogus IPv6 on dual-stack carriers (Hamrah-e-Aval). This is a correctness fix; it also avoids accidentally displaying an unexpected address family to the user.

---

## Remaining recommendations (by priority)

### Medium
- **Plain-HTTP geo probe.** `NetProbe.GEO_PROVIDERS` includes `ip-api.com:80` (cleartext). It only fetches coarse country/geo of an *already-known* IP, so no secret leaks, but it is observable and spoofable by a network attacker (could show a wrong flag). Prefer the TLS providers first and treat HTTP as last-resort, or drop it. Cleartext is allowed by the manifest because there is no `usesCleartextTraffic=false` / network-security-config.
  - _Suggested:_ add `res/xml/network_security_config.xml` allowing cleartext only for `ip-api.com`, and set `android:networkSecurityConfig` + `android:usesCleartextTraffic="false"`.
- **Split-tunnel app list visibility.** Added a scoped `<queries>` for launcher apps only (not `QUERY_ALL_PACKAGES`), which is the privacy-preserving choice and Play-policy safe. Good as-is; just don't switch to `QUERY_ALL_PACKAGES`.

### Low
- **DataStore is not encrypted.** `ProfileStore` persists the connection profile (protocol, manual peer/range, split app list) in plaintext DataStore. None of this is a credential, but on a rooted/backup-enabled device it is readable. `allowBackup="false"` is already set (good). Optional: move to EncryptedSharedPreferences/Tink if profiles ever hold secrets.
- **No code shrinking/obfuscation.** Release build should enable R8 (`isMinifyEnabled = true`) to strip and obfuscate — raises the bar for static analysis and reduces size.
- **Manual peer/range input is not validated in-app.** The engine normalizes it (`normalize_cidr_v4`), but adding light client-side validation prevents confusing "scanned nothing" states.
- **Logging.** Confirm `Diagnostics`/logcat never prints the full endpoint list or user IP at release level; keep verbose engine logs behind the `--verbose` flag (off by default).

### Informational / already good
- Minimal permissions: `INTERNET`, `ACCESS_NETWORK_STATE`, foreground-service, notifications. No location, contacts, storage.
- VPN service is not exported and is `BIND_VPN_SERVICE`-guarded; the QS tile is properly permission-gated.
- The engine performs its own encryption (WireGuard/MASQUE/QUIC); the app does not roll its own crypto.
- Own package is always excluded from the tunnel (`addDisallowedApplication(packageName)`), preventing a routing loop and accidental self-proxying.

---

## Quick wins checklist
- [ ] Add `network_security_config.xml`, set `usesCleartextTraffic="false"`, allow cleartext only for `ip-api.com` (or remove the HTTP provider).
- [ ] Enable R8 (`isMinifyEnabled`, `isShrinkResources`) for release.
- [ ] Optional: EncryptedSharedPreferences for the profile store.
- [ ] Keep `<queries>` scoped to LAUNCHER; never add `QUERY_ALL_PACKAGES`.
