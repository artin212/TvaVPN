# Security audit — 1.2.9 (mobile app security review, round r2)

**Target:** Aether Mobile, `versionName 1.2.9`, base `versionCode 13`, engine core `1.9.0`.
**Scope:** the whole shipped source tree — 73 Kotlin files (~24,000 lines) in
`app/src`, the Gradle build and signing configuration, `AndroidManifest.xml` and
`res/xml/*`, the vendored Rust engine (`native/aether`), the embedded
`psiphontunnel-2.0.39.aar`, and CI (`.github/workflows/build.yml`).
**Version policy:** this round changes **no** version. `versionName` stays
`1.2.9`, `versionCode` stays `13`, `PATCHLEVEL` stays `1.2.9`.

**Overall score: 79 / 100.**
One finding accounts for almost the entire gap (§1.1, the release signing key
committed to the repository). With that key rotated out and R8 enabled, the same
tree scores ≈ 88 without any other change.

---

## 0. The crash that opened this round

Reported symptom: with **Aether alone** and with **chained Aether → Psiphon**, the
app closes on its own roughly a minute or two after connecting, and the crash
screen on the next launch shows:

```
Thread: DefaultDispatcher-worker-6
java.lang.IndexOutOfBoundsException: No group 1
  at java.util.regex.Matcher.group(Matcher.java:589)
  at kotlin.text.MatcherMatchResult$groupValues$1.get(Regex.kt:382)
  at studio.cluvex.aether.ai.AiRedaction.redactLine$lambda$3(AiRedaction.kt:149)
  at kotlin.text.Regex.replace(Regex.kt:194)
  at studio.cluvex.aether.ai.AiRedaction.digest(AiRedaction.kt:125)
  at studio.cluvex.aether.ai.AiSession$analyze$2.invokeSuspend(AiSession.kt:651)
```

### 0.1 Root cause

`AiRedaction.kt:149` was:

```kotlin
out = IPV6.replace(out) { m -> maskIpv6(m.groupValues[1]) }
```

`IPV6` is written entirely with **non-capturing** groups — `(?:…)` — because its
three branches are alternatives, not fields. The pattern therefore has **no group
1**, and `groupValues[1]` throws `IndexOutOfBoundsException` the first time the
pattern matches anything at all. `IPV4` on the line below happens to have one
capture group, which is why the same idiom worked there and hid the asymmetry.

Why it fires on every session, a minute or two after connecting:

1. The engine writes `[+] identity ready: device=… ipv4=… ipv6=2606:…` on **every**
   connect at debug verbosity, so the log tail always contains an IPv6 literal.
2. `MainActivity` calls `AiSession.analyze(auto = true)` at the end of the
   *connected* branch — after the tunnel self-test and after the exit-IP probe,
   which waits up to 100 s. That delay is the "minute or two".
3. `analyze` builds the digest inside `scope.launch { … }`. `SupervisorJob`
   prevents sibling cancellation; it does **not** catch an unhandled exception, so
   the throw reached the thread's default handler and killed the process. The
   `AetherApp` crash handler wrote `last_crash.txt` on the way out — which is
   exactly the file the user saw on the next launch.

The transport (Aether alone vs chained) is irrelevant, which matches the report:
both modes reach the same call site.

### 0.2 Fix (root cause, not symptom)

* `AiRedaction.redactLine` now masks the **whole match** (`m.value`, group 0,
  which every match has) for both address families, so the code no longer depends
  on how a pattern happens to be bracketed. Labelled rules read their label
  through `groupValues.getOrElse(1)`.
* `AiRedaction.digest` redacts through `safeRedactLine`: if any single line ever
  throws again, that line is replaced by `[REDACTION-FAILED-LINE-DROPPED]` — never
  by its raw text, because turning a crash into a data leak is not a fix — and the
  rest of the digest is still produced.
* `AiSession.scope` gained a `CoroutineExceptionHandler`. An optional AI advisor
  must never be able to take a VPN tunnel down with it: a failure is now logged,
  any `Running` state is resolved so the UI cannot hang on a spinner, and the app
  keeps running.
* `analyze` builds the digest inside `runCatching` and reports a normal failure
  instead of dying, because that step runs *before* the request and so was outside
  every existing error path.

### 0.3 Two real leaks found while fixing it

* **IPv4-mapped IPv6.** The old rules matched `::ffff:203` and stopped, leaving
  `.0.113.9` in the digest as loose text — three octets of the public address the
  rule exists to hide. Mapped forms are now matched whole and masked with the IPv4
  policy (`::ffff:203.0.x.x`), with private/loopback quads kept intact.
* **JSON-shaped identifiers.** Roughly half of this app's log is Psiphon's JSON
  notices. `sessionId=` matched; `"sessionId":"5a4c…"` did not, so the Psiphon
  session identifier travelled to the model verbatim. Both the credential rule and
  the identifier rule now accept the quoted form.

### 0.4 Verification

`AiRedactionTest` went from 8 to 12 cases. The new ones cover: every IPv6 text
form (full, compressed, `::`, `::1`, link-local, bracketed, mapped), a realistic
mixed session log through `digest`, and the JSON-shaped identifiers. The whole rule
set was additionally replayed line by line over the user's real 258-line session
log: 44 lines redacted, **0** public IPv4 addresses surviving, and the timestamps,
`127.0.0.1:1819`, the Rust module paths (`aether::wg_prober`) and the build stamp
all intact — the pre-fix code threw on that same log.

> Note on process: every assertion in `AiRedactionTest` would have failed the same
> way before this fix. The tests existed and were never run against the build.
> `gradle :app:testReleaseUnitTest` should be a CI step; it currently is not.

---

## 1. Hardcoded secrets and keys

### 1.1 F-1 — CRITICAL (carried over from 1.2.7-r2, still open by design)

`.github/ci-keystore.jks.b64` is the **release signing key**, committed to the
repository, and its password (`aether-ci-keystore`) is in clear text in
`app/build.gradle.kts`. Anyone who can read the repo can build an APK that Android
accepts as an **in-place update** of this app: same package name, same certificate,
no warning, full `BIND_VPN_SERVICE` privileges over every byte the victim sends.
For a censorship-circumvention VPN this is the highest-impact issue in the project.

It cannot be closed by editing a file — it needs a **key rotation with a migration
plan** (`docs/SECURITY_AUDIT_1.2.7-r2.md` §1.1), because that certificate is what
every published release was signed with. 1.2.9 already reduced the blast radius:
the fallback is opt-in (`-PaetherAllowPublicCiKey=true`), a release build with no
credentials fails fast instead of quietly signing with a public key, and any build
that does use it prints a loud warning. **Score impact: this single finding is
most of the 21 points.**

### 1.2 Runtime secrets — clean

* No API key, token, password or private key is hardcoded anywhere in `app/src`
  or in the Rust engine. Grepped for labelled assignments of long literals, for
  `AIza…` shapes, and for base64 blobs; the only matches are the CI keystore
  password above and *names* of preference keys in `SecretStore`.
* The Gemini key is the **user's own**, entered by the user, sealed with AES-256-GCM
  under a non-exportable Android Keystore key, sent as an `x-goog-api-key`
  **header** (never a `?key=` query parameter, which would land in far more logs),
  and scrubbed out of anything the AI layer sends — including out of the log
  digest, so it cannot be echoed back through a request authenticated with it.
* WireGuard/WARP key material is generated on the device by the engine; nothing
  is shipped with the APK. `app/src/main/assets/server_entries.txt` is Psiphon's
  **public** server list, not a secret.
* `keystore.properties` is git-ignored and only `keystore.properties.example`
  ships.
* Server addresses are not secrets here by design: Cloudflare's edge ranges are
  public infrastructure and the engine discovers endpoints by scanning them.

**Section score: 55 / 100.**

---

## 2. Cryptography and protocols

* **No weak or obsolete primitive is used.** No MD5, SHA-1, DES/3DES, RC4, ECB, no
  `java.util.Random` for anything security-bearing. The app-side vault is
  AES-256-GCM (`AES/GCM/NoPadding`, 12-byte random IV, 128-bit tag,
  `setRandomizedEncryptionRequired(true)`). Tunnel crypto is WireGuard
  (ChaCha20-Poly1305/Curve25519) and MASQUE/QUIC via vendored `quiche` + BoringSSL.
* **TLS is the platform's**, with no customisation: `SSLSocketFactory.getDefault()`
  and `HttpsURLConnection` only. minSdk 26 means TLS 1.2 minimum, 1.3 where the OS
  provides it.
* **No custom `TrustManager`, no `X509TrustManager` stub, no permissive
  `HostnameVerifier` anywhere in the tree.** That is the single most common
  MitM-enabling defect in mobile VPN clients and it is absent here.
* **Hostname verification is explicit on every hand-rolled TLS path** — the AI
  client, the geolocation probe, the Psiphon front and the reachability check all
  call `getDefaultHostnameVerifier().verify(host, session)` after
  `startHandshake()`, because `startHandshake()` validates the chain but **not**
  the name when the socket is created over an existing connection. Without that
  check anyone holding a certificate for any domain could read the user's API key
  and prompts. Verified at all four call sites.
* **F-4 — LOW: no certificate pinning.** Trust is the system CA store, so a device
  with an attacker-installed root CA (or a locally compromised store) can intercept
  the AI conversation. Deliberate trade-off, and the right one here: pinning
  Google's `generativelanguage.googleapis.com` chain in a client that ships from a
  GitHub release and cannot hot-fix quickly turns a routine Google certificate
  rotation into a dead feature, and the tunnel's own traffic does not depend on it.
  If pinning is added, pin the SPKI of the intermediate with a backup pin and a
  documented expiry.

**MitM verdict: no exploitable path found** for the app's own connections.

**Section score: 88 / 100.**

---

## 3. Data-leak risk (DNS, IPv6, bypass)

* **DNS.** The AI client and the geolocation probe both send the destination to the
  proxy as a SOCKS5 **DOMAIN** address (`ATYP=0x03`) so the *exit* resolves it. The
  obvious alternative, `java.net.Proxy(SOCKS)`, resolves locally and would emit a
  query on the operator's resolver, off-tunnel — a leak that also advertises intent.
  The TUN itself is given `1.1.1.1`/`8.8.8.8`, so device DNS goes inside the tunnel.
* **IPv6.** `::/0` is routed into the TUN when leak protection is on, and
  **unconditionally in chained mode** — 1.2.7-r3 traced "it shows a sanctions
  error while connected" to exactly this: with no `::/0` route the phone's real
  IPv6 address served every AAAA destination. In chained mode the TUN also carries
  no IPv6 *address*, which makes Android's own resolver stop handing out AAAA per
  network, while `::/0` still swallows any IPv6 an app produces itself. No leak
  path found in either family.
* **Kill switch.** `setBlocking(true)` when the user enables it, so the interface
  never silently falls back to direct traffic while the tunnel is not forwarding.
* **Bypass.** Exactly one package is excluded from the TUN: the app's own, and it
  must be — the engine's outer sockets would otherwise route into the tunnel they
  create. The consequence is documented and gated in code: because the app is
  outside the tunnel, the AI feature refuses to run unless it can dial the tunnel's
  local SOCKS proxy (`AiGate`), so "the app's traffic is off-tunnel" never turns
  into "the AI request left in the clear". Split-tunnel INCLUDE/EXCLUDE modes were
  re-checked: blocked apps stay *inside* the TUN so the filter bridge can drop
  their packets, rather than being excluded into direct internet.
* **F-5 — MEDIUM: LAN sharing is an unauthenticated proxy.** With sharing on,
  `ShareBridge` exposes SOCKS5 and HTTP proxies on `0.0.0.0` with no credential and
  no IP allow-list; anyone on the same Wi-Fi can use the tunnel, and their traffic
  is attributed to the user's exit. Mitigations already in place: off by default,
  loopback-only bind otherwise, and a UI warning. Recommend a generated
  username/password (SOCKS5 user/pass auth is one message) or a subnet allow-list.

**Section score: 90 / 100.**

---

## 4. Local storage

* **Sealed properly:** the Gemini API key and the Cloudflare Access token/secret
  live in `SecretStore` — AES-256-GCM with a non-exportable Keystore key,
  ciphertext in `MODE_PRIVATE` SharedPreferences, unreadable on another device, and
  dropped rather than kept when the key is invalidated.
* **F-2 — MEDIUM: `diagnostics.log` is plaintext.** App-private and excluded from
  every backup path, but it holds the endpoints the scan settled on, the exit IP,
  the WARP enrolment identifier and the assigned IPv6 — precisely the "who was this
  user talking to" set. On a rooted or seized device it is readable. Recommend
  encrypting it in place with the same Keystore key, or scoping it to the session
  and purging on disconnect with an explicit opt-in for persistence.
* **F-3 — MEDIUM: the engine's `aether.toml` stores the WireGuard private key**
  (base64) and the WARP device identity in plaintext in app-private storage. Same
  exposure class as F-2. Ideally the engine reads its identity from a sealed blob
  handed over by the app.
* **F-6 — LOW: the DataStore preference file is plaintext** (manual endpoints, scan
  ranges, MTU, chosen model). Not credentials, but on a hostile network the manual
  endpoint list is sensitive metadata. Accepted for now; backups are disabled.
* `last_crash.txt` holds a stack trace only — no addresses, no credentials.
* Backups are denied twice over: `android:allowBackup="false"` plus explicit
  `backup_rules.xml` and `data_extraction_rules.xml` that exclude every domain for
  both cloud backup and device-to-device transfer.

**Section score: 72 / 100.**

---

## 5. Permissions and manifest

| Permission | Verdict |
| --- | --- |
| `INTERNET` | Required. |
| `ACCESS_NETWORK_STATE` | Required (network-change reconnect). |
| `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_SPECIAL_USE` | Required for a long-running VPN; the subtype property is declared. |
| `POST_NOTIFICATIONS` | Required for the ongoing tunnel notification. |

* **No over-privilege found.** No `QUERY_ALL_PACKAGES` — split tunnelling uses a
  `<queries>` launcher-intent filter instead. `REQUEST_INSTALL_PACKAGES` and the
  in-app updater were removed in 1.2.2, so the app has **no runtime code-delivery
  path at all**. No location, storage, contacts, camera or phone-state permission.
* `android:allowBackup="false"`, `android:usesCleartextTraffic="false"`,
  `networkSecurityConfig` set. **`android:debuggable` is never set** (and the one
  logcat write in the tree is behind `if (BuildConfig.DEBUG)`).
* **Exported surface is minimal:** `MainActivity` (launcher) and the Quick Settings
  tile (guarded by `BIND_QUICK_SETTINGS_TILE`). The VPN service is
  `exported="false"` behind `BIND_VPN_SERVICE`; the widget receiver is not exported
  and is toggled by an explicit-component broadcast; `CrashReportActivity` is not
  exported. **No content provider is exported at all** — the `FileProvider` went
  with the updater in 1.2.2.
* Release signing is v2 + v3 with **v1 (JAR) signing disabled** — correct at
  minSdk 26 and it removes the Janus attack surface.

**Section score: 96 / 100.**

---

## 6. Logging

* **Release builds do not print to logcat.** The only `Log.*` call on a hot path is
  guarded by `BuildConfig.DEBUG`; everything else goes to the in-app diagnostics
  log, which is a deliberate product feature (users need it to diagnose blocking).
* **No credential is ever logged.** The Gemini key, the Access token and the
  WireGuard private key never reach a log call; the engine prints its *public*
  identity (device handle, assigned addresses) and endpoint results.
* **The AI path is redacted, and now tested** — see §0 and §7 below.
* Note, not a finding: the "copy log" button copies the log **unredacted**, by
  deliberate user action, for pasting into a bug report. The stored file is
  size-capped (`MAX_FILE_BYTES`) and the in-memory buffer is capped at 800 lines.
  Consider offering a "copy redacted" variant that reuses `AiRedaction`, so the
  redaction written for the model also protects the user's own bug reports.

**Section score: 85 / 100.**

---

## 7. Code quality and network configuration

* **Cleartext traffic is denied app-wide** (`usesCleartextTraffic="false"` plus a
  `network-security-config` with no per-domain exemption and no
  `cleartextTrafficPermitted="true"` anywhere). The only plain-HTTP use is the
  geolocation probe over raw sockets, which carries no user data and is exempt from
  the policy by construction — flagged so it is not mistaken for coverage.
* **Third-party attack surface is unusually small.** Dependencies: AndroidX
  core/activity/lifecycle, Compose BOM 2024.10.01, Material 3, DataStore 1.1.1,
  kotlinx-coroutines 1.9.0, JUnit 4 (test only), and the vendored
  `psiphontunnel-2.0.39.aar`. **No HTTP client, no analytics, no crash-reporting
  SDK, no ad SDK, no tracking library** — nothing that phones home besides the
  tunnel itself and the user's own Gemini key. No CVE-bearing library was
  identified in this set; the two components worth tracking upstream are the
  Psiphon AAR (built 2026-06-19, `cd31fecf7b`) and the vendored `quiche`/BoringSSL
  in `native/aether`, since a vendored TLS stack ages in place and is not patched
  by a Gradle bump. Recommend recording their upstream revision per release and
  re-syncing on each upstream security release.
* **F-7 — LOW: R8 is off** (`isMinifyEnabled = false`, `isShrinkResources = false`).
  Obfuscation is not a security control, but on a tool used under surveillance,
  ship-ready class and string names make an APK trivially easy to fingerprint and
  to re-target, and a never-exercised `proguard-rules.pro` means enabling it later
  is a risky change rather than a flag flip. The JNI keep-rule is already written.
* Build integrity is good: dependency repositories are declared with an independent
  Maven Central mirror as fallback, the release build fails fast without a stable
  keystore, split APK version codes are monotonic, `SOURCE_MANIFEST.sha256` pins
  every file in the tree, and CI cross-checks `PATCHLEVEL` against the
  `AETHER-BUILD-STAMP` compiled into `libaether.so`.
* Gap: **CI does not run the unit tests.** §0 is what that costs. Add
  `gradle :app:testReleaseUnitTest` before `assembleRelease`.

**Section score: 80 / 100.**

---

## What the AI feature sends — and what it never sends

The 1.2.9 advisor and chat run **only** if the user pastes their own Gemini API
key and turns the feature on. What leaves the device is a **redacted digest** of the
diagnostics log, capped at 220 lines and 12,000 characters, sent over TLS through
the tunnel's own local SOCKS5 proxy.

Removed before anything leaves the device (`AiRedaction`, 12 unit tests):

* the user's Gemini API key, `AIza…`-shaped keys, and any `key`/`token`/`secret`/
  `password`/`authorization`/`bearer`/`client_id` value — in bare **and** JSON form;
* the WARP `device=` enrolment handle, `installation_id`, `session_id` (including
  Psiphon's JSON `"sessionId"`) and every bare UUID;
* public IPv4 addresses, masked to their /16 (`188.114.x.x`);
* public IPv6 addresses, masked to their /32 (`2606:4700:x:x`) — the assigned WARP
  IPv6 is globally routable and stable, so a full /128 *is* the subscriber;
* IPv4-mapped IPv6 addresses, masked as addresses (`::ffff:203.0.x.x`).

Deliberately kept, because they are plumbing rather than identity and they are what
makes a diagnosis possible: `127.0.0.1:1819`, RFC1918 / loopback / link-local
addresses, timestamps, protocol and error text, and the build stamp.

**Never sent at all:** browsing history, DNS queries, visited domains, packet
payloads, traffic contents, contacts, files, device identifiers, location, and the
raw unredacted log. The app has no analytics and no telemetry: with the AI feature
off — the default — it makes **no** request to any AI service, and even with it on,
the only AI endpoint is Google's, authenticated with the user's own key. If the
redactor ever fails on a line, that line is **dropped**, not sent raw.

---

## Score

| # | Area | Weight | Score |
| --- | --- | --- | --- |
| 1 | Secrets & key management | 20 | 55 |
| 2 | Cryptography & protocols (TLS, MitM) | 15 | 88 |
| 3 | Data-leak risk (DNS, IPv6, bypass) | 20 | 90 |
| 4 | Local storage | 15 | 72 |
| 5 | Permissions & OS configuration | 10 | 96 |
| 6 | Logging | 10 | 85 |
| 7 | Code quality & network config | 10 | 80 |
| | **Weighted total** | **100** | **79** |

## Priority order

1. **F-1 (CRITICAL)** — rotate the release signing key out of the repository, per
   the migration plan in `docs/SECURITY_AUDIT_1.2.7-r2.md` §1.1. Nothing else in
   this report comes close.
2. **F-2 / F-3 (MEDIUM)** — encrypt or session-scope `diagnostics.log`, and seal
   the engine identity file that holds the WireGuard private key.
3. **F-5 (MEDIUM)** — authenticate the LAN sharing listeners.
4. Run the unit tests in CI (the §0 crash shipped past 8 tests that would have
   caught it), then enable R8 (**F-7**) and consider pinning (**F-4**).
