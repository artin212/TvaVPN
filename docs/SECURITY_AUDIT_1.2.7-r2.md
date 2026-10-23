# Security audit — AetherMobile **1.2.7** (pass 3, mobile app + VPN pipeline)

Scope: full `app/src/main` Kotlin source, `AndroidManifest.xml`, all `res/xml` security configs,
Gradle build + signing, `scripts/`, `.github/`, and the Psiphon transport pipeline.
Version audited and shipped: **1.2.7** (`versionCode 11`) — unchanged by this pass.
Method: manual review against the seven requested categories, plus four issues found outside them.

Severity: **C**ritical / **H**igh / **M**edium / **L**ow / **I**nformational.

---

## 0. Summary

| # | Finding | Sev | State |
|---|---------|-----|-------|
| 1.1 | Release **signing key committed to the repository**, password in `build.gradle.kts` | **C** | Reported + build-time warning added; needs a key rotation (cannot be fixed by an edit) |
| 1.2 | Exit-IP badge fetched over **cleartext HTTP first** → spoofable "you are protected" | **H** | **Fixed** (TLS provider first) |
| 1.3 | **IPv6 dead-end** in chained mode: v6 routed into a v4-only exit | **H** | **Fixed** (instant local refusal + no v6 UDP) |
| 1.4 | DNS fallback could be **refused by policy**, leaving the device unable to resolve | **H** | **Fixed** (DNS-over-HTTPS 443 fallback, TLS + hostname verified) |
| 1.5 | Diagnostics log leaked **browsing metadata** (one line per failed destination) | **M** | **Fixed** (destination no longer logged) |
| 1.6 | Diagnostics log leaked **org / endpoint metadata** via engine argv echo | **M** | **Fixed** (argv values redacted) |
| 1.7 | udpgw **connection-id collision** could cross-deliver one flow's UDP replies to another | **M** | **Fixed** |
| 1.8 | Unbounded `conids` map → memory growth over a long session | **L** | **Fixed** |
| 1.9 | Unsynchronised iteration over a `synchronizedMap` (`releaseFlowsFor`) | **L** | **Fixed** |
| 1.10 | Release builds ship **unminified/unobfuscated** (`isMinifyEnabled = false`) | **L** | Accepted, documented |
| 1.11 | LAN share exposes an **unauthenticated** SOCKS5 + HTTP proxy | **M** | Accepted (opt-in, defaults to loopback), documented |
| 1.12 | Psiphon `PropagationChannelId` / `SponsorId` are placeholder values | **I** | Documented |

Nothing in categories 1, 2, 5 or 6 of the brief was found to be exploitable beyond the items above.
No hardcoded API key, symmetric key, private key, password or bearer token exists anywhere in
`app/src/main`.

---

## 1. Findings

### 1.1 Release signing key is public — **CRITICAL**

`.github/ci-keystore.jks.b64` is the **release signing keystore**, committed to the repository, and
`app/build.gradle.kts` contains its password in clear text (`aether-ci-keystore`, store + key).
`.gitignore` excludes `*.jks`; the `.b64` suffix is what got this past it.

**Impact.** Anyone who can read the repository can build an APK with the same `applicationId` and
the same certificate. Android accepts it as an **in-place update** — no warning, no uninstall, and
`.github/expected-signer-ci.txt` will happily confirm the fingerprint matches. The attacker then
holds `BIND_VPN_SERVICE` over every byte the victim's device sends. For a censorship-circumvention
VPN whose users are targeted by network-level adversaries, this is the highest-impact issue in the
project, and it defeats the entire Play-Protect / stable-signature effort documented in
`docs/SIGNING.md`.

**Deliberately not "fixed" in this pass.** Deleting the key would break in-place updates for every
existing user and would not undo the exposure — the key is already public. It requires a rotation,
which is a release decision, not a code edit. What was added: a loud configuration-time warning on
every release-producing build that uses this keystore, and this document.

**Remediation plan.**
1. Generate a new keystore **offline** (`scripts/generate-keystore.sh`); never commit it.
2. Store it as a CI secret (base64 in GitHub Actions secrets, decoded to a temp file at build time
   and deleted afterwards) — not as a repository file.
3. Publish the **new** SHA-256 signer fingerprint in the release notes and in-app About panel, and
   tell users the next update requires one manual uninstall + reinstall. Sign that transition
   release with the old key as well if you can, so the switch itself is verifiable.
4. Treat the old certificate as **compromised**: revoke it in `expected-signer-ci.txt`, and add a
   CI check that fails if any file under `.github/` decodes to a JKS/PKCS12 container.
5. Rotate anything else that key protected.

### 1.2 Exit-IP badge came from cleartext HTTP — **HIGH, fixed**

`NetProbe.GEO_PROVIDERS` tried `ip-api.com:80` (plain HTTP) **first**, so on a healthy network the
IP and country shown in the UI always arrived unauthenticated. An on-path adversary can rewrite
that response and make the badge show a plausible foreign IP and flag while traffic is not
tunnelled at all — turning the app's own reassurance into the attack. On the networks this app
exists for that is not theoretical.

**Fixed.** The TLS provider (`www.cloudflare.com:443`, chain-verified **and** hostname-verified —
that verification was already correct, and is confirmed in §3) is now tried first; the cleartext
providers remain only as an availability fallback. The country *refinement* still uses ip-api over
HTTP because its free tier has no TLS; that is now documented in code as informational only and
must never be read as proof of protection.

### 1.3 IPv6 was routed into an IPv4-only exit — **HIGH, fixed**

The TUN advertises an IPv6 address and `::/0`, so apps prefer `AAAA`. Psiphon exits are IPv4-only in
practice, so those flows died at the exit after a full round trip. Two consequences, one security
and one functional: every IPv6 connection attempt leaked the *destination* to the exit server in a
refused port forward for no benefit, and the resulting refusal storm made the health watchdog
convict healthy servers (see `docs/PSIPHON_MEDIA_STALL.md`).

**Fixed.** `PsiphonSocksFront` latches the verdict after two refusals and then refuses IPv6 flows
locally and instantly (`REP_HOST_UNREACHABLE`), so nothing leaves the device. IPv6 UDP is dropped on
the same verdict. Leak protection is untouched: the `::/0` route stays, so IPv6 still cannot escape
around the tunnel.

### 1.4 DNS could be refused into total failure — **HIGH, fixed**

When a server refused the udpgw intercept, DNS fell back to **TCP/53** — a port most Psiphon servers
do not allow out. Every lookup became a refused port forward: name resolution failed, and the
watchdog read the failures as censorship. A device that cannot resolve anything is a security
problem, not only a usability one — it pushes users to disable the tunnel.

**Fixed.** A refused TCP/53 dial latches, and lookups move to **DNS-over-HTTPS on 443** (RFC 8484)
through the same tunnel. The resolver host travels as a SOCKS5 hostname so Psiphon resolves it
inside the tunnel (no name is ever resolved locally, so nothing leaks to the carrier's resolver).
TLS is validated against the system trust store and the hostname is checked explicitly — the same
MitM defence as §3's probe path, deliberately reused rather than reinvented.

### 1.5 / 1.6 Metadata in the persisted diagnostics log — **MEDIUM, fixed**

The log lives in app-private storage, **but it survives crashes on disk and it is the file users are
asked to attach to a bug report.** Two leaks:

* `ShareBridge` logged `host:port` for every failed upstream dial — one line per flow, i.e. a
  partial browsing history. **Fixed:** the destination host is gone; only the port and the reason
  remain, which is all that distinguishes a broken upstream from a blocked destination.
* `AetherProcess` echoed the engine's full argv, which carries the Zero Trust **team name**, any
  hand-pinned gateway, the chosen resolvers and the routing rules. **Fixed:** values of `--team`,
  `--peer`, `--dns`, `--route-block`, `--route-direct` are masked; the flags are kept because they
  are what makes the log useful.

No **secret** was ever on the command line — `accessClientSecret` and `accessToken` go through the
environment (`ConnectionProfile.toEnv`), which is correct: `/proc/<pid>/cmdline` is world-readable
to co-located apps, the environment block is not. Engine stdout is already kept out of Logcat in
release builds. Both verified.

### 1.7 / 1.8 / 1.9 udpgw multiplexer defects — **MEDIUM / LOW, fixed**

* Connection ids were allocated as `counter and 0xFFFF`, which wraps after 65535 flows and can
  return an id the server still has a remote socket bound to — delivering **one flow's UDP replies
  to a different flow**, i.e. a cross-flow data leak inside the device. Now checked against live
  flows and never 0.
* `conids` was an unbounded `ConcurrentHashMap` that outlived the `flows` map it pointed into.
  Now bounded together with `flows` (access-ordered LRU; eviction drops both).
* `releaseFlowsFor` iterated a `synchronizedMap` without holding its monitor. Now synchronized.

### 1.10 Release builds are unminified — **LOW, accepted**

`isMinifyEnabled = false`. R8 is not a security control, but shipping full symbol names makes
reverse-engineering the anti-DPI logic trivial, which matters for a circumvention tool whose
adversary studies the client. Accepted here because minifying a Compose + JNI + reflection-adjacent
build needs its own validation pass; if you enable it, keep the existing `-keepclasseswithmembernames
class * { native <methods>; }` rule and re-verify `TProxyService` symbol names, which the native
bridge resolves by exact string.

### 1.11 LAN sharing is unauthenticated — **MEDIUM, accepted**

With sharing on, SOCKS5 `0.0.0.0:10810` and HTTP `0.0.0.0:10811` accept **any** device on the
network with no credentials. Already opt-in, already defaults to loopback (`localOnly = true`,
fixed in the 1.2.7 pass-2 audit), already warned about in the UI. Recommendation for a future
release: SOCKS5 username/password with a generated per-session credential shown in the share panel.

### 1.12 Psiphon `PropagationChannelId` / `SponsorId` are placeholders — **INFORMATIONAL**

`FFFFFFFFFFFFFFFF` / `1111111111111111`. Not a secret and not a vulnerability (they are public
identifiers), but they are also not valid Psiphon network identifiers, so server-side tactics,
authorizations and traffic rules are whatever the network gives an unknown client — which is part of
why some servers behave so differently from others. Worth obtaining real values, or documenting the
choice.

---

## 2. Category-by-category results

**1. Hardcoded secrets and keys.** No API key, token, password, private key or symmetric key in
`app/src/main`. The two long base64 constants in `PsiphonTransport` are Psiphon's **public**
server-list and server-entry signature verification keys — correct to embed, and the reason a
tampered server list can be rejected. Zero Trust credentials are never in source, never in argv, and
are sealed at rest (§4). Server addresses are not secrets: `assets/server_entries.txt` is Psiphon's
public bundled list. **One real secret was found, and it is not in the app: the signing key, §1.1.**

**2. Cryptography and protocols.** No MD5, SHA-1, DES, 3DES, RC4, ECB or hand-rolled crypto
anywhere. At-rest secrets use **AES-256-GCM with a non-exportable Android Keystore key**
(`SecretStore`: `setRandomizedEncryptionRequired(true)`, 12-byte IV prepended, 128-bit tag,
undecryptable ciphertext dropped rather than kept). Transport crypto is delegated to
psiphon-tunnel-core and the Rust engine — in scope for their own audits, not re-implemented here.
**TLS/MitM:** the app has **no** custom `TrustManager`, **no** custom `HostnameVerifier`, no
`ALLOW_ALL_HOSTNAME_VERIFIER`, and no `checkServerTrusted` override — the classic Android MitM
holes are all absent. Where TLS is used over a raw socket (`NetProbe.tlsWrap`, and now the DoH
fallback) the chain is validated by the system trust store **and** the hostname is verified
explicitly, because `SSLSocket.startHandshake()` does not do the second check. No certificate
pinning: accepted and deliberate — pinning a circumvention client's own endpoints hands the censor a
fingerprint, and the geo probes are non-authoritative by design.

**3. Data-leak risk (DNS / IPv6 / bypass).** DNS is remote by construction: hostnames go to the
proxy as SOCKS5 `ATYP_DOMAIN` and are resolved inside the tunnel; nothing in the app resolves a
user destination locally. DNS-over-udpgw carries the `FLAG_DNS` bit so the exit answers from its own
resolver. IPv6: leak protection routes `::/0` into the tunnel by default, and §1.3 now makes the
dead-end fail closed instead of leaking destinations to the exit. Bypass surfaces are three,
all intentional and all audited: the app's own package is excluded from the TUN (loop prevention —
required, and the reason the engine can reach the network at all), `--route-direct` (explicit user
rule), and split tunnelling (explicit user choice). The kill switch (`setBlocking(true)`) and strict
mode close the disconnect window. The `PROBE_TARGETS` watchdog and geo probes run **inside** the
app's excluded package by design and carry no user data.

**4. Insecure local storage.** Zero Trust secrets: AES-GCM sealed, Keystore-backed, in their own
`SharedPreferences` file, cleared on reset. Everything else in DataStore is preferences (MTU, scan
mode, split lists) — losing them is cosmetic, and they are excluded from every backup transport.
The diagnostics log is app-private, size-capped at 512 KB, and after §1.5/§1.6 no longer carries
destinations or org identifiers. No world-readable file, no `MODE_WORLD_*`, no external storage
write, no unencrypted database. `hev.yaml` contains only loopback ports and MTU.

**5. Permissions and manifest.** Five permissions, all required and none dangerous: `INTERNET`,
`ACCESS_NETWORK_STATE`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE`,
`POST_NOTIFICATIONS`. No `QUERY_ALL_PACKAGES` (a scoped `<queries>` launcher intent instead), no
`REQUEST_INSTALL_PACKAGES` (removed with the in-app updater, so there is no runtime code-delivery
path at all), no `READ/WRITE_EXTERNAL_STORAGE`, no location, no phone state.
`android:allowBackup="false"`, and both `backup_rules.xml` and `data_extraction_rules.xml` exclude
every domain for cloud backup *and* device transfer. `android:debuggable` is never set.
No exported component is reachable by a third party: `MainActivity` is the launcher;
`AetherVpnService` is `exported="false"` and permission-gated to `BIND_VPN_SERVICE`;
`AetherTileService` is exported but gated to `BIND_QUICK_SETTINGS_TILE` (system-only, correct);
the widget receiver is `exported="false"` and its toggle broadcast is sent with an explicit
component; `CrashReportActivity` is `exported="false"`. **No content provider is exported at all.**
Verified clean.

**6. Insecure logging.** Engine stdout is mirrored to Logcat **only** in debug builds
(`if (BuildConfig.DEBUG)`) — Logcat is readable via adb and lands in bug reports. Nothing prints
payload bytes, keys or full URLs. Psiphon's own notices are recorded verbatim, and psiphon-tunnel-core
already redacts addresses (`[redacted]` in the field log) — confirmed against `loge.txt`. After
§1.5/§1.6 the persisted log carries no destination hosts and no organization identifiers.
Remaining by design: exit IP and country, protocol, and local port numbers.

**7. Code quality and network config.** `cleartextTrafficPermitted="false"` app-wide via
`network_security_config.xml`; the only plain-HTTP traffic left is `NetProbe`'s raw-socket geo probe,
which is exempt by mechanism and, after §1.2, no longer the primary source of the badge.
Dependencies are a small, current, first-party set (Compose BOM 2024.10.01, AndroidX core 1.15.0,
lifecycle 2.8.7, DataStore 1.1.1, coroutines 1.9.0) with **no** networking or serialisation
third-party library — so the usual CVE surface (OkHttp/Retrofit/Gson/Jackson) simply is not present.
No known-vulnerable version among them. The heavy lifting is native: psiphon-tunnel-core, the
vendored Rust engine and quiche, and hev-socks5-tunnel — pin and track those upstreams, because
they are where a CVE would actually reach users. `enableV1/V2/V3Signing` are all on and a hard gate
refuses to emit a debug-signed release. Build-time repository fallback (Maven Central + Google
mirror) is availability, not a trust downgrade: both are HTTPS and Gradle verifies module metadata.
**Recommend adding Gradle dependency verification (checksum/signature pinning) and enabling
`dependencyLocking`** — currently a compromised mirror is trusted by default.

---

## 3. Verified clean (checked, nothing to fix)

Custom trust managers / hostname verifiers (none), weak ciphers (none), `SecureRandom` misuse (no
`Random()` for security purposes anywhere), exported providers (none), `WebView` (none — so no
`addJavascriptInterface`, `setAllowFileAccess` or `loadUrl` injection surface at all), dynamic code
loading / `DexClassLoader` / `Runtime.exec` on user input (the only `exec` is the bundled engine
binary with a sanitised, validated argument list), path traversal in file writes (all in `filesDir`
with fixed names), pending-intent mutability, `SharedPreferences` world access, StrictMode-visible
disk I/O on the main thread (the log writer and pings are off-thread by construction), and argument
injection into the engine CLI (`sanitizedDns`, `sanitizedRules`, `sanitizedRange`,
`sanitizedTlsGroups`, `sanitizedUpstream` all reject whitespace and shell metacharacters, and the
engine re-validates).

---

## 4. What changed in code in this pass

`PsiphonSocksFront.kt`, `PsiphonHealth.kt`, `AetherVpnService.kt`, `PingMonitor.kt`,
`AetherProcess.kt`, `ShareBridge.kt`, `NetProbe.kt`, `app/build.gradle.kts`.
Version stays **1.2.7 / versionCode 11**. See `docs/PSIPHON_MEDIA_STALL.md` for the performance
half and the field-log evidence behind each change.
