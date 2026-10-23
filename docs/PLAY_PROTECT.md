# The install warning in `a1`, and what WhiteAestherMobile actually does

## The finding, first

**WhiteAestherMobile does not avoid this warning.** It documents it as expected
behaviour. From its own `README.md`, in the install section:

> Android will warn about installing outside the Play Store. That is expected for
> a sideloaded APK.

There is no method in that project to port, because there is no method. This was
checked directly against the source, not inferred:

| Checked | WhiteAestherMobile | Aether 1.2.9 |
| --- | --- | --- |
| Published on Google Play | **No.** `docs/PLAY_STORE_SUBMISSION.md` lists the required in-app VPN disclosure as *"absent. No such screen is in the source."* and calls it "the one blocking item" | No |
| Play App Signing / Play Integrity | Not used. No `com.google.android.play` dependency anywhere | Not used |
| Distribution | GitHub Releases: split APKs, a universal APK, `SHA256SUMS`, plus an `.aab` marked *"Play Store submission, not for sideloading"* | GitHub Releases |
| Anything Play-Protect-specific in the build | Nothing. `grep -ri "play protect"` over the whole repo returns no hits | Nothing |

The first project you sent, **WhiteAesther**, is even further from it: it is a
Tauri *desktop* app. Its release workflow produces `nsis`/`msi`, `dmg` and
`deb`/`rpm`/`AppImage`. There is no APK, no Gradle, no `AndroidManifest.xml`, and
therefore nothing that Play Protect ever looks at.

## Why no build change can remove that specific dialog

The `a1` screenshot is **not** the harsh red "App blocked to protect your device"
banner. It reads:

> Play Protect hasn't seen this app before. To protect your device and data, send
> this app to Google for a security scan.

That is Play Protect's *unknown-app scan prompt*. It fires when the
`(package name, signing certificate)` pair is not present in Google's corpus of
apps it has already seen. It is keyed on **reputation**, not on APK contents. An
APK can be perfectly signed with a v2+v3 chain, minified, `targetSdk` current and
free of sensitive permissions, and still show it — because none of those things
tell Google the app exists.

There are exactly three ways it goes away, and all three are distribution
decisions rather than code changes:

1. **Publish on Google Play.** The listing itself is the reputation. For this app
   that means completing the Play Console `VpnService` declaration — the same
   blocking item WhiteAestherMobile has open.
2. **Let the scan run.** Tapping *"Send app to Google"* once submits the APK.
   Google scans it, and installs of that same APK stop prompting. This is worth
   documenting for users because it is free and it works, but note the privacy
   trade below.
3. **Accumulate installs of one stable certificate.** Reputation builds per
   certificate. This is the slow path, and it only works if the certificate never
   changes — which is the one part of this that *is* a build concern, and which
   this release does tighten.

### The privacy caveat on option 2

Do not push option 2 at your users without saying what it costs. Submitting the
APK for scanning uploads the binary to Google, from a device in Iran, running a
circumvention tool. For most users that is fine. For a user whose threat model
includes their device being examined, "I chose to send a VPN app to Google" is a
signal, and the *"Install without scanning"* button in that same dialog is the
right choice for them. State both; do not pick for them.

## What was ported, and what was deliberately not

WhiteAestherMobile's signing setup is genuinely better than this project's in one
important respect, and that has been ported.

**Ported.**

1. **The committed release key is no longer used automatically.**
   `.github/ci-keystore.jks.b64` is a release signing key committed to this
   repository, with its password in cleartext in `app/build.gradle.kts`. Anyone
   who can read the repo could build an APK that Android accepts as an in-place
   *update* of this app: same package, same certificate, no warning, full VPN
   privileges. `.gitignore` excludes `*.jks`; the `.b64` suffix is what got it
   past that. WhiteAestherMobile has no key in its repo at all — the key comes
   from CI secrets or the build produces nothing.
   The fallback is now opt-in (`-PaetherAllowPublicCiKey=true`). CI is unaffected;
   it supplies `KEYSTORE_PATH` and never used this path.
   **This is still the highest-severity issue in the project and it is not fixed
   by this change.** The file has to stay until the key is rotated, because it is
   the certificate every published release was signed with, and deleting it breaks
   updates for everyone who already installed one. The migration plan is in
   `docs/SECURITY_AUDIT_1.2.7-r2.md` 1.1.
2. **v1 (JAR) signing turned off.** `minSdk` is 26; v1 is only consulted by
   Android 6 and older, so on every device this app supports it is unused weight,
   and it is the scheme the Janus class of attacks targets. WhiteAestherMobile
   ships v2+v3. The certificate does not change, so updates still install.
3. **`android:usesCleartextTraffic="false"`** stated explicitly in the manifest,
   alongside the existing network security config. WhiteAestherMobile sets both.

**Deliberately not ported, and why.**

- **`isMinifyEnabled` / `isShrinkResources`** (both `true` in
  WhiteAestherMobile, both `false` here). Worth doing, and not worth doing blind.
  R8 on this codebase would run against JNI entry points, three `org.json` call
  sites and Compose, and a broken `keep` rule surfaces as a crash in a shipped
  VPN app rather than a build failure. It needs a device pass on
  `docs/DEVICE_TEST_PLAN.md`, not a one-line edit.
- **`targetSdk` 35 → 36.** A behaviour change for every permission, foreground
  service and background rule in the app. Not something to carry in as a side
  effect of an AI bug-fix release. WhiteAestherMobile itself pins `targetSdk` at
  36 while compiling against 37 and comments that raising it is a deliberate,
  separate decision.

Neither of those affects the `a1` dialog either.
