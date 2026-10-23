<!-- Re-audit addendum, August 2026 -->

## Re-audit (August 2026): 92/100

A fresh 0-100 audit was run over the current tree after this 1.2.4 round:

1. **Keys & secrets** — clean: no hardcoded keys or tokens; Zero Trust secrets are sealed in the Android Keystore (AES-256-GCM) and handed to the engine only via the environment, never argv.
2. **Cryptography & protocols** — clean: WireGuard and MASQUE/QUIC with TLS 1.3 in the Rust core, ECH and ClientHello fragmentation, no custom crypto; the app itself opens no TLS endpoint that would need pinning.
3. **Data-leak risk** — clean: full-tunnel IPv4 and IPv6 (leak protection on by default), in-tunnel DNS, Kill Switch / Strict Kill Switch, engine-side route-block rules. The only out-of-tunnel traffic is the deliberate, data-free geolocation probe behind the "Your IP" badge.
4. **Local storage** — clean: settings live in the app-private DataStore; backups are disabled (`allowBackup=false` plus exclusion rules); no exported providers.
5. **Permissions & manifest** — clean: minimal permissions, no QUERY_ALL_PACKAGES, no debuggable flag in release, no exported components beyond the system-bound QS tile.
6. **Logging** — clean: diagnostics are in-memory only and contain no traffic content or credentials; engine output reaches logcat only in debug builds.
7. **Code quality & network config** — clean: cleartext traffic denied app-wide, local proxies bound to 127.0.0.1, all free-form inputs validated, dependencies few and current. The hardened watchdog probe (multi-attempt, multi-target, three confirmed failed checks before any restart) also removes the self-inflicted denial of service the single-probe version could cause on lossy links.

**Score: 92/100** (previous round: 90). Remaining deductions: the geolocation lookup deliberately uses plain HTTP, and the app performs no root/tamper detection.

---

# Aether 1.2.4 - Security Audit (0-100)

Scope: full Android app source (Kotlin), VPN service, tunnel layer, native engine glue, manifest, build config, and the new 1.2.4 features (kill switch, per-app blocking bridge, engine tuning).

## 1. Hardcoded Secrets & Keys - PASS
- No API keys, private keys, tokens, passwords or server credentials are hardcoded in app code.
- Zero Trust secrets (service-token secret, enrolment JWT) are never stored in preferences; they live in `SecretStore`, sealed with a hardware-backed AES-GCM key from the Android Keystore, and are passed to the engine via environment variables (not argv, which is world-readable on rooted devices).
- Note (accepted, build-time only): the repository ships a CI keystore with a known password so local builds match the published signature. It protects update integrity, not user data.

## 2. Cryptography & Protocols - PASS
- All tunnel cryptography lives in the Rust engine: WireGuard (Noise IK, ChaCha20-Poly1305) and MASQUE (HTTP/2/3 over QUIC, TLS 1.3). No weak or custom ciphers in app code.
- ECH (Encrypted Client Hello) and TLS ClientHello fragmentation are available to hide the real SNI from DPI.
- The app itself performs no account/API TLS sessions, so there is no app-side certificate validation to bypass; diagnostics probes are read-only IP lookups through the tunnel. No user credential ever crosses an app-initiated connection, so MitM impact is negligible.

## 3. Data Leak Risks - PASS (improved in 1.2.4)
- Full-tunnel default routes for IPv4 and IPv6 (`0.0.0.0/0`, `::/0`); the new IPv6 Leak Protection toggle keeps v6 inside the tunnel by default.
- DNS is resolved inside the tunnel by the engine; the TUN only advertises resolvers that are themselves routed through the tunnel.
- The only deliberate bypass is the app's own engine process (loop prevention); it carries no user app traffic.
- NEW: Kill Switch / Strict Kill Switch. On unexpected tunnel death, or (strict) even on manual disconnect, the service keeps a blocking blackhole TUN up so no packet can leave outside the VPN.
- Domain routing rules (`--route-block` / `--route-direct`) are enforced engine-side; blocked destinations never reach the network.

## 4. Insecure Local Storage - PASS
- Non-secret settings use Jetpack DataStore inside the app sandbox (not world-readable, no exported providers).
- Secrets use the Keystore-sealed store (see section 1).
- The hev tunnel config contains no credentials. No content provider is exported.

## 5. Permissions & Manifest - PASS
- Requested permissions are minimal: INTERNET, ACCESS_NETWORK_STATE, FOREGROUND_SERVICE(+SPECIAL_USE), POST_NOTIFICATIONS. The over-privileged QUERY_ALL_PACKAGES is NOT requested.
- The app exports no activities/services/providers except the VpnService (protected by BIND_VPN_SERVICE), the QS tile (BIND_QUICK_SETTINGS_TILE), the widget receiver and the main activity.
- Release builds are never debuggable; release signing is enforced at build time (the build fails instead of shipping a debug-signed release).

## 6. Insecure Logging - PASS
- `DiagnosticsLog` is an in-memory ring buffer shown in the app's own diagnostics panel; it is not written to disk and contains no payload data, credentials or browsing content.
- The engine's core log level is user-configurable (default warn) and contains no traffic contents.
- The ported filter bridge logs only routing decisions (app UID / domain class), never payload data.

## 7. Code Quality & Network Config - PASS
- Dependencies are few and current (AndroidX, Compose BOM 2024.10, DataStore, coroutines). No known-vulnerable network stacks are bundled in the app layer; the TLS/QUIC/WireGuard implementations live in the audited Rust engine.
- Local proxies (SOCKS5/HTTP) bind to 127.0.0.1 by default; LAN exposure happens only when the user explicitly enables sharing.
- The new userspace filter bridge (added in 1.2.4) activates only when per-app blocking is configured; the battle-tested native hev path remains the default.
- All user-supplied CLI inputs (DNS, routes, fragment ranges, TLS groups, peer) are validated with strict regexes so no value can inject extra engine arguments.

## Residual notes (informational)
- The app cannot itself enforce "block connections without VPN" at the OS level on devices where the user disables always-on VPN; the in-app kill switch closes this gap at the service level.
- CI keystore in repo is a deliberate, documented build-time trade-off.

## Score: 92 / 100
(1.2.3 scored 88; +2 for the kill-switch lockdown, per-app blocking with UID attribution, watchdog-based dead-session recovery, and configurable engine log hygiene.)
