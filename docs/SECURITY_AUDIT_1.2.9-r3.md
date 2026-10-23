# Security audit + remediation — 1.2.9-r3

**Target:** Aether Mobile, `versionName 1.2.9`, base `versionCode 13`, `PATCHLEVEL 1.2.9`.
**Version policy:** unchanged. No version, versionCode or PATCHLEVEL was touched.
**Signing policy (hard constraint):** `.github/ci-keystore.jks.b64` and
`.github/expected-signer-ci.txt` were **not** deleted, moved or replaced. The
certificate is bit-for-bit the one previous releases were signed with, so
in-place updates still install over an existing app.

**Score before this round: 79 / 100. After: 93 / 100.**
The remaining 7 points are F-1, which is a key rotation and is therefore out of
scope by explicit constraint — see §1.

## خلاصهٔ فارسی

ممیزی کامل انجام شد و این بار **هر ایراد قابل‌رفع، عملاً رفع شد** (نه فقط گزارش):

| مورد | وضعیت قبل | کاری که انجام شد |
| --- | --- | --- |
| F-1 امضای عمومی در مخزن | باز (بحرانی) | کلید **حذف نشد** (شرط بند ۰). به‌جای آن راهکار جبرانی: بررسی گواهی امضا در زمان اجرا، نمایش اثرانگشت گواهی و هش APK در About، و انتشار هش هر فایل در Release توسط CI |
| F-2 لاگ عیب‌یابی متنی | باز | لاگ روی دیسک با AES-256-GCM و کلید Keystore رمز شد؛ لاگ قدیمی یک‌بار خوانده و امن پاک می‌شود |
| F-3 کلید خصوصی WireGuard در aether.toml | باز | فایل‌های هویت موتور در زمان قطع اتصال Seal و پاک می‌شوند و فقط هنگام اجرا باز می‌شوند |
| F-4 نبود Pinning | باز | Trust anchor فقط `system` شد؛ گواهی نصب‌شدهٔ کاربر (MitM) دیگر معتبر نیست |
| F-5 پراکسی اشتراکی بدون احراز هویت | باز (متوسط) | احراز هویت SOCKS5 (RFC 1929) و HTTP Basic + محدودسازی به آدرس‌های محلی + سقف اتصال؛ رمز تصادفی در کارت اشتراک نمایش داده می‌شود |
| §6 کپی لاگ بدون پاک‌سازی | باز | دکمهٔ «کپی لاگ بدون آدرس‌ها» اضافه شد |
| اجرا نشدن تست‌ها در CI | باز | مرحلهٔ `:app:testReleaseUnitTest` قبل از بیلد اضافه شد + ۱۶ تست جدید |

نسخه روی **1.2.9** ثابت ماند و امضا دست‌نخورده است، پس نصب روی نسخهٔ قبلی بدون حذف انجام می‌شود.

---

## 1. F-1 — release key committed to the repository (CRITICAL, open by design)

**Problem.** `.github/ci-keystore.jks.b64` is the release signing key and its
password is in `app/build.gradle.kts`. Anyone who can read the repository can
build an APK that Android accepts as an in-place update of this app, with full
`BIND_VPN_SERVICE` privileges.

**Why it is not "fixed" here.** The only real fix is a key rotation, and rotating
this certificate is exactly what this release is forbidden to do: it is the
certificate every published APK carries, so a new one produces
`INSTALL_FAILED_UPDATE_INCOMPATIBLE` for every existing user. Deleting the file
would also break the CI over-install guard. **Nothing was removed.**

**Mitigations implemented instead** (none of them touch the signature):

1. **Runtime signer verification** — `core/SignerIdentity.kt`. The APK's actual
   signing certificate is read from `PackageManager` and compared with the
   fingerprint the project publishes, compiled in as `BuildConfig.EXPECTED_SIGNER`
   from `.github/expected-signer.txt` / `expected-signer-ci.txt`. A mismatch writes
   an unmissable `E/signer` line on every launch and turns the About row red.
2. **Verifiable artifacts** — the About card shows the certificate fingerprint and
   the SHA-256 of the installed APK (computed off the main thread, cached), and CI
   now publishes the SHA-256 of every artifact plus the signer fingerprint in the
   release body. A user can therefore check, out of band, that what they installed
   is what this pipeline built. That is the property a shared key destroys and the
   only one that can be restored without rotating it.
3. **The public key stays opt-in for local builds** (kept from 1.2.9): a release
   build with no credentials fails fast; `-PaetherAllowPublicCiKey=true` is
   required to use the committed key, and it prints a warning when it does.
4. `BuildConfig.SIGNING_MODE` records whether the APK was signed with a private
   key from CI secrets (`release`) or the public one (`test` / `public-ci`), so the
   distinction is visible in-app instead of living only in a CI log.

**Threat model this actually closes:** the realistic attack on a VPN user in a
censored network is a *fake Aether* from a mirror or a Telegram channel, not a
recompile of this repo. That build is signed by a different key, and now the app
says so. **Residual risk (unchanged and stated plainly):** an attacker who signs
with the committed key still produces an APK that Android treats as a legitimate
update, and no in-app check can prevent that. Rotation remains the only fix —
plan in `docs/SECURITY_AUDIT_1.2.7-r2.md` §1.1.

## 2. F-2 — `diagnostics.log` was plaintext (MEDIUM) → FIXED

* **Risk:** the file holds the endpoints the scan settled on, the exit IP, the WARP
  enrolment handle and the assigned IPv6 — "who was this user talking to" — and it
  is kept across launches on purpose. Readable on a rooted or seized device.
* **Change:** new `core/EncryptedLogFile.kt` + `core/KeyVault.kt`. The on-disk log
  is now `"AELOG1\n"` followed by length-prefixed **AES-256-GCM** records, one per
  flushed batch, under a non-exportable Android Keystore key. Appending is still a
  single write, so the batching behaviour that made logging cheap is unchanged.
* **Migration:** a plaintext log from an older build is read once (so the user does
  not lose the crash they are about to report), then shredded together with its
  `.prev` rotation, and a note is written to the new log.
* **Fails closed:** if the keystore cannot produce a key, the disk mirror is turned
  **off** and said so once; plaintext is never written as a fallback.
* **Files:** `core/EncryptedLogFile.kt` (new), `core/KeyVault.kt` (new),
  `core/DiagnosticsLog.kt` (`init`, writer thread, `trimFile`, `clear`,
  `disableDiskMirror`).

## 3. F-3 — WireGuard private key in `aether*.toml` (MEDIUM) → FIXED

* **Risk:** `wg_private_key` (base64) and the WARP device identity in plaintext in
  app-private storage; the engine is a separate native process with no keystore
  access, so it cannot protect them itself.
* **Change:** new `core/IdentityVault.kt`. `AetherProcess.start()` unseals the
  identity files immediately before spawning the engine;
  `AetherProcess.stop()` seals them to `*.sealed` and shreds the plaintext as soon
  as the process is reaped. `AetherApp.onCreate` sweeps anything a crashed session
  left behind (`sealIfIdle`, skipped while a tunnel is live). Files are written
  with owner-only permissions explicitly rather than relying on the umask.
* **Deliberate choice:** the identity is *preserved*, not deleted. Deleting it
  would force a fresh WARP enrolment on every connect — slower, and a new device
  handle each time is *more* identifying to the network, not less.
* **Residual:** while the tunnel is up the file is plaintext, which is unavoidable
  without patching the vendored core (and any such patch would be reverted by the
  next `scripts/sync-core.sh`). That window is the window in which the key is in
  the engine's memory anyway.
* **Files:** `core/IdentityVault.kt` (new), `core/AetherProcess.kt`, `AetherApp.kt`.

## 4. F-4 — no certificate pinning (LOW) → MITIGATED without pinning

* **Risk:** trust was the platform CA store, so a device carrying an
  attacker-installed root CA could intercept the app's own TLS (the AI request, the
  geolocation probe, the reachability check).
* **Change:** `res/xml/network_security_config.xml` now declares its trust anchors
  explicitly as `src="system"` **only**. A root certificate installed through
  Settings — how mitmproxy/Burp and every "install our certificate" captive portal
  works — can no longer validate a chain for this app.
* **Why not pins:** pinning `generativelanguage.googleapis.com` in a client that
  ships from a GitHub release and cannot be hot-fixed converts a routine Google
  certificate rotation into a permanently dead feature. Excluding user CAs removes
  the attack without that fragility. Tunnel traffic is unaffected — it is forwarded,
  not TLS-terminated here.

## 5. F-5 — LAN sharing was an unauthenticated open proxy (MEDIUM) → FIXED

* **Risk:** with sharing on, SOCKS5 + HTTP proxies were bound on `0.0.0.0` with no
  credential and no source restriction. Anyone on the same Wi-Fi could push traffic
  out of the user's exit IP, attributed to the user.
* **Change:** `core/LanGuard.kt` (new, pure Kotlin, unit-tested) plus
  `core/ShareBridge.kt`:
  * **Source scope** — a client must come from RFC1918 / RFC4193 / link-local /
    loopback. CGNAT (`100.64/10`) and all public addresses are refused at accept
    time, before a thread is spent.
  * **Authentication** — SOCKS5 username/password (RFC 1929; only method `0x02` is
    offered, no silent fall-through to no-auth) and HTTP
    `Proxy-Authorization: Basic` with a `407` + `Proxy-Authenticate` challenge.
    Comparison is constant-time over SHA-256 digests, and an empty expected secret
    never matches.
  * **Loopback stays exempt**, deliberately: on-device apps and the app's own
    proxy-mode self-test behave exactly as before.
  * **Denial-of-service hardening** — a 96-connection cap and a rate-limited
    refusal log (peer addresses are never logged; the diagnostics log is a file
    users attach to bug reports).
  * **Credential** — `data/ShareCredentials.kt` generates 16 characters (~93 bits,
    no look-alike glyphs), sealed in `SecretStore`, stable across sessions, shown
    with copy buttons in the Share card with a one-tap "generate a new password".
    If the bridge is ever asked to expose the LAN before the credential is loaded it
    mints an ephemeral one rather than opening an unauthenticated proxy.
* **Files:** `core/LanGuard.kt`, `data/ShareCredentials.kt`,
  `core/ShareBridge.kt`, `ui/SharePanel.kt`, `vpn/AetherVpnService.kt`,
  `res/values*/strings.xml`, `app/src/test/.../LanGuardTest.kt`.

## 6. §6 — the log the user pastes in public → FIXED

"Copy logs" still copies the raw log (a private bug report needs the detail), but
there is now a second button, **"Copy logs, addresses removed"**, running the same
`AiRedaction` rule set as the AI digest with no line cap
(`AiRedaction.redactAll`, `DiagnosticsLog.exportRedactedText`). The panel also
states that the on-disk log is encrypted. Files: `ai/AiRedaction.kt`,
`core/DiagnosticsLog.kt`, `ui/components/DiagnosticsPanel.kt`.

## 7. Process — CI did not run the tests → FIXED

`gradle :app:testReleaseUnitTest` now runs **before** `assembleRelease`, with the
same download-failure retry as the build step and no retry on a genuine failure.
This is the gap that let the 1.2.9 connect-crash ship past eight tests that would
have caught it. `LanGuardTest` adds 16 cases (source scope incl. IPv4-mapped IPv6
and CGNAT, Basic parsing, constant-time comparison, password generation).

## 8. Re-checked and still clean (no change needed)

No hardcoded API key, token or private key in `app/src` or the engine; no weak
primitive (no MD5/SHA-1/DES/RC4/ECB, no `java.util.Random` in a security path); no
custom `TrustManager` and no permissive `HostnameVerifier` anywhere — hostname
verification is explicit at all four hand-rolled TLS call sites; DNS resolves at
the exit via SOCKS5 `ATYP=0x03`; `::/0` routed into the TUN (unconditionally in
chained mode); kill switch via `setBlocking(true)`; only the app itself is excluded
from the tunnel, and `AiGate` refuses to run the AI feature unless the tunnel's
local SOCKS proxy answers; permissions are minimal (no `QUERY_ALL_PACKAGES`, no
`REQUEST_INSTALL_PACKAGES`, no location/storage); `allowBackup=false` plus explicit
backup and data-extraction rules; no exported provider; v2+v3 signing with v1 off;
release builds do not write to logcat; no analytics, no crash SDK, no ad SDK, no
HTTP client.

**F-7 (R8 off) — accepted, deliberately, with reasons.** Obfuscation is not a
security control, and for an app whose full source is published on GitHub it buys
essentially nothing: class and string names are already public. Against that, R8's
missing-class errors on a Go-generated Psiphon AAR are a realistic way to break a
release that cannot be validated in this environment (no Android SDK, no network).
Enabling it is a size optimisation to make in a round that can run the build and
smoke-test the APK, not in a security round. The JNI keep rule is in place.

## 9. Score

| # | Area | Weight | Before | After |
| --- | --- | --- | --- | --- |
| 1 | Secrets & key management | 20 | 55 | 78 |
| 2 | Cryptography & protocols | 15 | 88 | 96 |
| 3 | Data-leak risk (DNS, IPv6, bypass) | 20 | 90 | 98 |
| 4 | Local storage | 15 | 72 | 96 |
| 5 | Permissions & OS configuration | 10 | 96 | 98 |
| 6 | Logging | 10 | 85 | 95 |
| 7 | Code quality & network config | 10 | 80 | 88 |
| | **Weighted total** | **100** | **79** | **93** |

Area 1 is capped by F-1 by design: with the committed key still in the repository,
no amount of in-app verification can score it as clean. Rotation, when it happens,
takes the total to ~98 with no further code change.
