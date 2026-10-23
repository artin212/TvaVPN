# Security audit — AetherMobile 1.2.7 (UI pass 2)

Scope: the whole shipping app (`app/`), the release pipeline (`.github/`,
`scripts/`), the vendored engine's app-owned patches, and every file this
revision touched. Version deliberately unchanged: **1.2.7, version code 11**.

Method: manifest and permission review, static grep sweeps for the standard
Android sink/anti-pattern set, data-at-rest review, listener/bind review,
IPC/export review, log-hygiene review, supply-chain and CI review. Each finding
below is either **FIXED in this pass**, **ACCEPTED (with reason)**, or
**VERIFIED CLEAN**.

---

## 1. Fixed in this pass

### 1.1 The LAN proxy bridge defaulted to fail-OPEN — **fixed** (highest severity found)

`ShareBridge.start(localOnly: Boolean = false, ...)` and `startSync(...)` bound
`0.0.0.0:10810` (SOCKS5) and `0.0.0.0:10811` (HTTP) unless a caller *remembered*
to say otherwise. Those listeners accept every client **without authentication**;
on a café or hotel network that is an open proxy on the device's address, and
anything that goes through it exits under the user's tunnel and identity.

Every current caller does pass the flag (`AetherVpnService` derives it from
`profile.lanShare`, `SharePanel` only starts it from the sharing toggle), so
nothing was exposed in the shipped build. The hazard was the default: one future
call site that omits the argument silently opens the device.

Fixed: the default is now `localOnly = true` (loopback), and the two LAN call
sites in `SharePanel` request exposure explicitly. The dangerous case is now the
one you have to ask for.

### 1.2 Backup rules were declared but never wired — **fixed**

`res/xml/backup_rules.xml` carried a "defense-in-depth" comment and was
referenced by **nothing**: `android:allowBackup="false"` was the only thing
between the app data dir and a cloud backup. The data dir holds the user's manual
endpoints and scan ranges (sensitive metadata on a hostile network), the
diagnostics log (engine output, exit IPs) and the sealed Zero Trust secrets.

Fixed: the manifest now declares `android:fullBackupContent="@xml/backup_rules"`
and a new `res/xml/data_extraction_rules.xml` that denies **cloud backup** and
**device-to-device transfer** for `file`, `database`, `sharedpref` and `external`
on Android 12+.

### 1.3 Dead export surface — **fixed**

`res/xml/file_paths.xml` still described the FileProvider that exported downloaded
APKs to the package installer. The provider and the updater went in 1.2.2; the
config outlived them and was an invitation to re-add the provider by accident.
Deleted, and registered in `.github/removed-sources.txt` so an in-place upgrade
over an older checkout cannot leave it behind.

### 1.4 Orphaned Tor build script — **fixed**

`scripts/build-tor.sh` survived the 1.2.7 Tor removal even though the README and
the changelog both state it was deleted. It cross-compiles a `libtor.so` that
nothing in the app loads any more, from pinned upstream tarballs, i.e. dead build
surface that could be resurrected into a release by a stray CI edit. Deleted,
registered for purge, and `scripts/purge-stale-sources.sh` now accepts
`scripts/` paths in the removal list (it previously only accepted `app/`,
`native/aether/aether/src/` and `docs/`).

### 1.5 Text corruption in the Persian README — **fixed**

Two lines contained U+FFFD replacement characters from an earlier bad transcode.
Cosmetic, but the affected line is the update/signing explanation, so it mattered.
Both repaired; the file is verified valid UTF-8 with zero replacement characters,
and its `<div>` nesting is now balanced (it had one stray closing tag).

---

## 2. Accepted risks (unchanged, by design, documented)

1. **The LAN sharing feature is unauthenticated by nature.** When the user turns
   it on, both proxies accept any client on the LAN. Mitigations: opt-in toggle,
   off by default, only bound while the tunnel is up, fixed documented ports, the
   panel states what it does, and engine core 1.8.0 additionally logs a warning
   when a listener is world-reachable.
2. **Loopback listeners are reachable by other apps on the device.** Any local app
   can dial `127.0.0.1:1819/1825/1827/10810/10811` and use the tunnel. This is
   inherent to a local-proxy VPN on Android; the alternative (UID filtering) is
   not available to an unprivileged app. No credential is exposed on those ports.
3. **`NetProbe` uses raw sockets for the geolocation probe**, which is exempt from
   the cleartext policy that `network_security_config.xml` enforces for the rest of
   the app. It carries no user data and only asks "what address am I coming out
   on"; that address is already known to the network.
4. **`isMinifyEnabled = false`.** No R8, so the APK is not obfuscated. Deliberate:
   this project's whole distribution model is reproducibility from public source,
   and obfuscation is not a security control. Nothing depends on it.
5. **`.github/ci-keystore.jks.b64` is a public test key** committed on purpose so
   in-place updates keep working for people who build in their own fork. It is
   *not* the key of an official release; `docs/SIGNING.md` explains the model and
   the build prints the signer fingerprint. Anyone shipping to real users must
   supply their own keystore via repository secrets.

---

## 3. Verified clean

- **Permissions:** `INTERNET`, `ACCESS_NETWORK_STATE`, `FOREGROUND_SERVICE`,
  `FOREGROUND_SERVICE_SPECIAL_USE`, `POST_NOTIFICATIONS`. No location, contacts,
  storage, `QUERY_ALL_PACKAGES` or `REQUEST_INSTALL_PACKAGES`. Split tunnelling
  uses a scoped `<queries>` launcher intent instead of the all-packages
  permission.
- **Exported components:** only `MainActivity` (launcher) and the Quick Settings
  tile, and the tile is guarded by `BIND_QUICK_SETTINGS_TILE`. The VPN service is
  `exported="false"` and guarded by `BIND_VPN_SERVICE`; the widget receiver is not
  exported and is toggled by an explicit-component broadcast; the crash screen is
  not exported. **No content provider is exported at all.**
- **Cleartext:** denied app-wide by `network_security_config.xml`.
- **`PendingIntent`:** every one of the five is `FLAG_IMMUTABLE`.
- **Secrets at rest:** Zero Trust credentials are sealed with a non-exportable
  AES-256-GCM key in the Android Keystore (`SecretStore`), randomized IV per
  write, and unreadable ciphertext is dropped rather than retained.
- **Secrets in flight to the engine:** the Access client secret/token/e-mail and
  the upstream-proxy URL (which can carry user:pass) are passed through the child
  process's **environment**, never as CLI arguments, because any local app can read
  `/proc/<pid>/cmdline` but not another process's environment. Verified again over
  `Profile.toArgs()` / `Profile.toEnv()`: no credential appears in `toArgs()`.
- **Log hygiene:** engine stdout is mirrored to Logcat **only** in debug builds
  (`BuildConfig.DEBUG`); in release it goes solely to the app-private diagnostics
  file, which is size-capped and rotated. The two remaining ungated `Log` calls
  are static native-loader strings with no data in them.
- **No TLS weakening:** no custom `X509TrustManager`, no `HostnameVerifier`
  override (both uses call the *default* verifier), no `ALLOW_ALL_HOSTNAME_VERIFIER`.
- **No WebView anywhere**, so no JavaScript bridge, no `loadUrl` sink.
- **No `MODE_WORLD_*`**, no external-storage writes, no dynamic code loading, no
  reflection into hidden APIs, and no `Runtime.exec` on user-supplied strings: the
  single `ProcessBuilder` runs the packaged engine binary with a
  programmatically-built argument list.
- **Bind addresses:** every chained-transport front binds `127.0.0.1` explicitly
  (`PsiphonSocksFront` listener, its upstream dial, and its UDP relay socket).
- **Kill switch and DNS:** the TUN is built with `setBlocking(true)` and the
  in-tunnel resolvers are installed on the interface, so a packet cannot escape to
  the local resolver while the tunnel is up.
- **Connected state is gated on real resolution:** the fifth self-test step speaks
  SOCKS5 `UDP ASSOCIATE` against the same port the device forwarder uses, so the
  app cannot report "Connected" over a session that could not resolve a name.
- **CI:** `permissions: contents: write` only, no interpolation of
  `github.event.*` into `run:` blocks (no script-injection sink), pinned toolchain
  versions, and the release step publishes from a file in the repo.
- **No hardcoded credentials** matched an API-key/password/secret/token pattern
  anywhere in `app/src` or the vendored engine sources.

---

## 4. This revision's UI changes, reviewed for risk

The fit-to-height pass, the connect-button change and the glow retune are pure
presentation: no new permission, no new listener, no new file, no new IPC, no new
network call, no change to any transport, profile field or persisted value. The
one behavioural note worth recording is a *robustness* one: `FitToHeight`'s search
starts at `1f`, only ever shrinks, is bounded to four passes and clamped at
`0.55f`, so it terminates by construction and cannot spin the layout phase.

**Result: no known vulnerability, leak or crash path outstanding in 1.2.7.**
