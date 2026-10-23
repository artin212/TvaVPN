# Build validation

- Source structure and Kotlin callback signatures were checked against the embedded
  Psiphon AAR (`PsiphonTunnel.HostService`). All fifteen interface methods are
  implemented; only the bodies of `onConnecting`, `onConnected`, `onExiting`,
  `onListeningSocksProxyPort` and `onAvailableEgressRegions` changed.
- Version is 1.2.7 / base versionCode 11. Deliberately NOT bumped in this revision.
- Every `when` over `TransportBackend` and `ExternalKind` was re-checked for
  exhaustiveness after `AETHER_TOR` and `ExternalKind.TOR` were REMOVED
  (`ui/AdvancedPanel.kt` backendLabel + backendHelp + the exit-country helper,
  `model/TransportBackend.kt` externalKind + pipelineLabel,
  `transport/ExternalTransport.kt` factory). The service and the UI branch on the
  capability predicates instead of on enum identity.
- Both profile persistence paths (DataStore and Intent codec) store the backend by
  NAME, so the appended enum values decode old saved profiles unchanged.
- Brace/paren balance and import usage were verified on every touched file.
- No native Tor step remains in release CI: this revision removed the backend, the two
  Kotlin files behind it and `scripts/build-tor.sh`. The Aether core and the
  Psiphon AAR are the only native inputs left.
- `.github/removed-sources.txt` lists `transport/TorTransport.kt` and
  `transport/TorSocksFront.kt`, so a build over an older checkout purges the
  orphans instead of compiling against symbols that no longer exist.
- The UI work was re-checked the same way: `ui/components/GlowCycle.kt` is
  new and owns the shared band drawing, the duplicate `TWO_PI` constant was
  removed from `ConnectionCard.kt` (a private top-level const would have clashed
  with the shared one in the same package), and every new string
  (`meta_ping_strength`, `ping_quality_*`) exists in both `values/` and
  `values-fa/`.
- A local Gradle compile was not run in the packaging environment: the input archive
  omits `gradlew` and `gradle-wrapper.jar`, and no Android SDK is available there.
  CI remains the authoritative full Android build.

## 1.2.7 stability + localisation revision

- Version deliberately unchanged: still 1.2.7 / base versionCode 11.
- `PsiphonTunnel.HostService` is still fully implemented; only the bodies of
  `onConnected`, `onListeningSocksProxyPort` and `onAvailableEgressRegions` changed,
  and no interface method was added or removed.
- `PsiphonHealth.bind` now hands the rotation callback a `(Rotation, Target)` pair.
  `PsiphonTransport` is the only caller and was updated with it; `Rotation` is still
  exhaustive over `RECONNECT`/`RESTART` in the one `when` that switches on it.
- New Psiphon config keys (`DisableReplay`,
  `EstablishTunnelServerAffinityGracePeriodMilliseconds`) are only emitted after a
  server has been convicted of filtering, and both exist in the embedded
  `psiphontunnel-2.0.39` Go library; unknown keys are ignored by the Go side, so an
  older core degrades to today's behaviour rather than failing to start.
- Region steering may only pick a code the library reported through
  `onAvailableEgressRegions`, because `EgressRegion` is a hard filter and steering
  into an empty region would hang establishment instead of fixing anything.
- `PsiphonSocksFront.retarget()` takes the object monitor and then `udpgwLock`, the
  same order as `start`, `stop` and `onServerRotated`, so no new lock cycle exists.
- RTL: `AetherTheme` is the single root of both Compose entry points
  (`MainActivity`, `CrashReportActivity`), so pinning `LocalLayoutDirection` there
  covers every screen, and `Dialog`/`Popup`/`DropdownMenu`/`ModalBottomSheet` inherit
  it through the same composition local.
- Typography: all fifteen Material 3 roles are re-cut, which is what Material's own
  components read; `FontFamily.Monospace` call sites (timers, transfer rates,
  IP:port, diagnostics log, crash trace) are intentionally left alone so numeric
  columns keep aligning.
- `res/font/vazirmatn_bold.ttf` is a static Bold face (no `fvar` table), so every
  `FontWeight` entry maps to it explicitly to avoid synthetic emboldening.
- Brace/paren balance and import usage re-verified on every touched file.
- A local Gradle compile was again not run: the packaging environment has no Android
  SDK, no Gradle wrapper and no network access. CI remains the authoritative build.

## 1.2.9 Gemini AI revision

- Version deliberately unchanged: `versionName` 1.2.9, base `versionCode` 13,
  `PATCHLEVEL` 1.2.9. No native source was touched, so the engine inside the APK
  still carries the `AETHER-BUILD-STAMP:1.2.9` the release workflow asserts.
- No dependency was added to `app/build.gradle.kts`. The AI layer uses `org.json`
  (Android platform), `javax.net.ssl` and `java.net` only, so the APK gains no
  third-party code - checked against every `import` in `ai/` and `ui/ai/`.
- No new permission and no manifest change: `INTERNET` was already declared, and
  the AI opens no component. `networkSecurityConfig` is untouched because every AI
  request is TLS to a single host through a local proxy.
- Every `when` introduced over a new enum (`AiGate`, `AiErrorKind`, `AiProbe`,
  `AiAdviceState`, `AiResult`) is exhaustive, and the two sealed hierarchies are
  `sealed interface` + `data class`/`data object` so the compiler enforces it.
- `AiPatch.WRITABLE` and `AiPatch.write()` were cross-checked key by key against
  `ConnectionProfile`: every writable key exists on the profile, every value is
  validated, and the numeric keys snap to `MTU_PRESETS` / `KEEPALIVE_PRESETS` /
  the reconnect-limit list the UI itself offers, so an AI-written value can never
  leave a picker showing a value the user cannot return to.
- The exclusions from `WRITABLE` were re-derived from the threat, not copied:
  `backend` (would disable the AI itself), `upstreamProxy` (redirects all traffic),
  `routeDirect`/`routeBlock` (a `direct` rule de-tunnels a domain),
  `manualPeer`/`manualRange` (pins an attacker-chosen edge), `proxyMode`,
  `splitMode`, `splitApps`, `blockedApps`, `lanShare` and every Zero Trust field.
- Both string files were verified programmatically: identical key sets (349 each)
  and identical positional format arguments per key. Every apostrophe in a new
  string is escaped, as aapt2 requires.
- Every `R.string.*` reference in `app/src/main/java` resolves to a declared
  string; the only unresolved match is `android.R.string.cancel`, which is a
  framework resource.
- Brace, paren and bracket balance was verified on every Kotlin file with a
  Kotlin-aware scanner (raw strings, string templates, char literals and nested
  block comments handled), including all nine new files and all six modified ones.
- Row metrics cannot regress for users who leave the AI off: the icon lives in
  `BaseRow` behind `AiTopicIcon`, which returns before emitting anything when
  hints are disabled or no provider is present, so a hints-off settings page
  composes exactly what 1.2.8 composed.
- `AI_CHAT` was made a navigation ROOT in `SettingsHost` alongside `QUICK`, and the
  stack builder rewritten around `root` so the back gesture from the home screen's
  AI button leaves settings instead of dropping the user into the settings list -
  the same bug 1.2.9 already fixed once for the tunnel shortcut.
- A local Gradle compile was again not run: the packaging environment has no
  Android SDK, no Gradle wrapper and no network access. CI remains the
  authoritative full Android build.

## 1.2.9-r2 crash fix + security audit revision

- Version deliberately unchanged: `versionName` 1.2.9, base `versionCode` 13,
  `PATCHLEVEL` 1.2.9, `native/aether/CORE_VERSION` 1.9.0. No native source was
  touched, so `libaether.so` still carries the `AETHER-BUILD-STAMP:1.2.9` the
  release workflow asserts, and the engine/APK patch-level cross-check still passes.
- Exactly three source files changed: `ai/AiRedaction.kt`, `ai/AiSession.kt` and
  `test/…/AiRedactionTest.kt`. Confirmed mechanically by regenerating
  `SOURCE_MANIFEST.sha256` before the doc updates: those three were the only hash
  differences in 4,179 files.
- No dependency added or removed, no permission change, no manifest change, no
  resource change (so no `values/` vs `values-fa/` divergence is possible), and no
  Gradle/signing change. `assembleRelease` inputs are byte-identical apart from the
  three Kotlin files.
- New imports: `kotlinx.coroutines.CoroutineExceptionHandler` in `AiSession.kt`
  (used by `crashGuard`). `MatchResult` in `AiRedaction.kt` is `kotlin.MatchResult`,
  auto-imported. No import became unused.
- `crashGuard` is a `CoroutineExceptionHandler` whose lambda body calls a private
  member function (`containFailure`) instead of touching `_advice` / `_thinking`
  directly, so no property is forward-referenced from an initializer.
- `runCatching { … }.getOrElse { … return@launch }` in `analyze` type-checks:
  `getOrElse` is inline, so the non-local return is legal and the block's type is
  `Nothing`.
- Brace/paren/bracket balance verified on all 73 Kotlin files with a Kotlin-aware
  lexer (comments, escaped strings, raw `"""` strings including `""""` regex
  terminators): all balanced.
- Every `R.string.*` and `@string/*` reference resolves in both `values/` and
  `values-fa/` (367 used, 375 defined in each). The only unresolved name is
  `android.R.string.cancel`, a platform resource.
- `.github/removed-sources.txt`: every listed path is absent from this tree, so the
  purge step has nothing to delete and cannot break the compile.
- Redaction behaviour was verified by re-implementing the exact patterns and mask
  functions on the JVM and replaying them: the pre-fix code throws
  `IndexOutOfBoundsException: No group 1` on the engine's own identity line, the
  post-fix code passes all 12 unit-test expectations, and over the reporter's real
  258-line session log it redacts 44 lines with 0 public IPv4 addresses surviving
  while leaving timestamps, `127.0.0.1:1819`, `aether::*` module paths and the build
  stamp intact.
- A local Gradle compile was again not run: the packaging environment has no Android
  SDK, no Gradle wrapper and no network access. CI remains the authoritative build.
  Recommended addition to CI: `gradle :app:testReleaseUnitTest` before
  `assembleRelease` — the crash fixed in this revision shipped past a test file that
  would have caught it, because the tests were never executed.

## 1.2.9-r3 security remediation + connected-mark revision

- Version deliberately unchanged: `versionName 1.2.9`, base `versionCode 13`,
  `PATCHLEVEL 1.2.9`. `.github/ci-keystore.jks.b64` and
  `.github/expected-signer-ci.txt` are byte-identical to the previous revision, so
  the signer fingerprint CI pins is unchanged and 1.2.9 users install this over
  their current app (v2+v3, v1 still off, `versionCode * 1000 + ABI` unchanged).
- New sources: `core/KeyVault.kt`, `core/EncryptedLogFile.kt`,
  `core/IdentityVault.kt`, `core/LanGuard.kt`, `core/SignerIdentity.kt`,
  `data/ShareCredentials.kt`, `ui/components/AetherMark.kt`,
  `app/src/test/.../core/LanGuardTest.kt`. No file was deleted, so
  `.github/removed-sources.txt` needs no entry and the purge step is a no-op.
- Brace/paren/bracket balance and import usage were verified mechanically on every
  touched file (18 files); the only import-usage hits are the Compose
  `getValue`/`setValue` delegate operators, which are used by `by` and were already
  present in those files.
- Every new `R.string.*` reference resolves: `scripts/purge-stale-sources.sh`
  reports "String-resource reference check: OK (467 references, all defined)", and
  all seventeen new strings exist in BOTH `values/` and `values-fa/`.
- `res/xml/network_security_config.xml` and both `strings.xml` files parse as XML.
- BuildConfig grew two fields (`EXPECTED_SIGNER`, `SIGNING_MODE`); both are plain
  string literals produced at configuration time from files that already exist in
  the repo, and both have a defined value when those files are absent ("" and
  "unsigned"), so a scratch clone still configures.
- `KeyVault` / `EncryptedLogFile` are only reached once `DiagnosticsLog.init` has a
  file, which happens in `Application.onCreate`. The JVM unit tests never set one,
  so `AiRedactionTest`, `AiModelPolicyTest` and `LanGuardTest` stay pure JVM tests
  with no Android keystore dependency.
- `LanGuardTest` uses only IP literals, so `InetAddress.getByName` performs no
  lookup and the suite runs offline on a CI runner.
- CI: `:app:testReleaseUnitTest` runs before `:app:assembleRelease`, sharing the
  build step's download-failure retry pattern and never retrying a real failure.
  The release body is now assembled at `${RUNNER_TEMP}/release-body.md`
  (release-notes.md + per-artifact SHA-256 + signer fingerprint) and referenced via
  `body_path: ${{ env.RELEASE_BODY }}`; `SIGNER_SHA256` is exported by the existing
  signature-verification step, which is unchanged in every other respect.
- A local Gradle compile was again NOT possible in the packaging environment (no
  Android SDK, no network, no `gradle-wrapper.jar` in the archive). CI remains the
  authoritative build. Every check that could be run without it was run.

## 1.3.0 — the first revision validated by an actual build

Everything below was RUN, not reasoned about. Toolchain: JDK 17.0.20, Android SDK
platform 35 + build-tools 35.0.0, Gradle 8.9, AGP 8.7.2.

- `gradle :app:compileReleaseKotlin` — **SUCCESS**. Warnings only (pre-existing
  `FlowPreview`, `allNetworks`, three AutoMirrored icon deprecations); no errors.
- `gradle :app:testReleaseUnitTest` — **SUCCESS, 52 tests, 0 failures, 0 skipped**:
  `AiRedactionTest` 12, `LanGuardTest` 15, `AiModelPolicyTest` 7 and the new
  `TorSocksWireTest` 18.
- `gradle :app:assembleRelease` — **SUCCESS**. All three splits packaged
  (arm64-v8a, armeabi-v7a, universal), R8 and resource shrinking included.
- LIMIT OF THAT APK: the native inputs were absent, so it contains only the
  dependencies' own `.so` files (`libgojni.so` from the Psiphon AAR,
  androidx.graphics.path, datastore) and NOT `libaether.so`, the hev tunnel or
  `libpt-lyrebird.so`. Building those needs the Rust toolchain plus NDK 26 cross
  compilation, which CI does. The APK proves the Kotlin, the resources, R8 and
  packaging; it is not a shippable build and was signed with a throwaway key.

### The three errors that build found

1. `LtrOutlinedTextField` had no `isError` parameter (the Tor reachability field
   passed one). Fixed in the component rather than by dropping the flag: a
   malformed `host:port` must be visible, because the engine falls back to port 443
   on a bad port instead of failing.
2. `TorSocksFront.readAddress` used an expression body (`= when (...)`) containing
   `return null`, which Kotlin prohibits. Converted to a block body; the early
   returns are the point, since a half-read address leaves the stream out of step
   with the client for every following byte.
3. `.github/removed-sources.txt` still listed `transport/TorSocksFront.kt` from when
   1.2.7 deleted it. 1.3.0 brings that path back, so the purge step deleted the new
   file, the CI commit step pushed the deletion to `main`, and the compile then
   failed with `Unresolved reference 'TorSocksFront'` in `AetherVpnService` — a
   source tree that looked broken while the only broken thing was the manifest.
   The line is gone and `scripts/purge-stale-sources.sh` now refuses to delete any
   listed Kotlin file whose name the current sources still reference, naming the
   line to remove instead.

WARNING for anyone updating an existing checkout of this repo: if a previous CI run
committed that deletion, the branch no longer contains
`app/src/main/java/studio/cluvex/aether/transport/TorSocksFront.kt`. Push the file
and the manifest fix together, or the next build repeats the deletion.
