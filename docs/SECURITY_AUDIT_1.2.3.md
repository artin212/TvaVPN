# Aether Android - Security Audit 1.2.3 (engine core v1.5.0)

Scope: the Android app in this repository (`app/`), its build/CI scripts, and
the way it launches and feeds the vendored Rust engine (`native/aether`,
core **v1.5.0**). The engine's own cryptography is upstream code and was
reviewed only where this app supplies data to it.

Method: manual source review of every Kotlin file, the manifest, the Gradle
and ProGuard configuration, the resource XML, and the CI workflow, plus a diff
review of the core upgrade 1.3.0 -> 1.5.0 that this release performs
(the *engine* went 1.3.0 -> 1.5.0; the *app* went 1.2.2 -> 1.2.3).

## Score: 88 / 100

| Area | Weight | Score | Notes |
|---|---|---|---|
| Secret handling | 20 | 18 | Zero Trust secrets sealed in Keystore AES-GCM; not in argv |
| Process & IPC surface | 15 | 14 | No exported components beyond the launcher and the QS tile |
| Network / TLS posture | 15 | 13 | Cleartext disabled app-wide; loopback-only exceptions |
| Input validation | 15 | 13 | New engine options are allow-listed before reaching argv |
| Build & supply chain | 15 | 13 | Core pinned per build, three-way merged, rollback path |
| Data at rest | 10 | 9 | Backups fully excluded; no plaintext credentials |
| Logging & diagnostics | 10 | 8 | Verbose logs debug-gated; secrets never logged |

## What this release changed, security-wise

### 1. Zero Trust credentials are treated as credentials (new)
Engine v1.5.0 introduces organization enrolment (`--team`, `--access-id`,
`--access-secret`, `--access-token`, `--access-email`). Two of these are
long-lived organization credentials.

Decisions taken:

* **Never on the command line.** Android exposes `/proc/<pid>/cmdline`; a
  service-token secret placed in argv would be readable far too widely. Only
  the non-secret team name and the `--gateway` flag travel via argv. The id,
  secret, JWT and e-mail are handed over through the engine's *environment*,
  which is exactly where `zerotrust.rs::TeamSettings::from_env()` reads them.
* **Never in the preferences file.** `SecretStore` seals the secret and the
  enrolment JWT with a non-exportable AES-256-GCM key generated inside the
  Android Keystore. The DataStore file keeps only non-sensitive fields
  (team name, client id, e-mail, method).
* **Masked in the UI**, so the values cannot be shoulder-surfed or captured in
  a screenshot.
* **Gateway is opt-in and labelled honestly.** Enabling the organization's
  Gateway proxy means the organization can log the user's browsing; the UI says
  so instead of presenting it as a neutral speed-up.

### 2. New engine options are validated before they become arguments
`--dns`, `--route-block` and `--route-direct` take free-form user text. Both
are allow-listed in `ConnectionProfile` (`sanitizedDns`, `sanitizedRules`)
against a strict pattern, de-duplicated, and hard-capped (8 resolvers, 256
rules) before a single token reaches the engine. A pasted blob containing
whitespace or shell metacharacters cannot split into extra arguments, and
cannot inflate argv without bound.

### 3. The core upgrade itself was reviewed, not just applied
`scripts/sync-core.sh` moved the engine from the vendored 1.3.0 sources to
upstream v1.5.0. The app's two engine patches (custom scan ranges in
`prober.rs` / `wg_prober.rs`) were **three-way merged**, not copied. Upstream
had refactored the CIDR constants into `masque_cidrs_v4()` /
`wg_prefixes_v4()`; both conflicts were resolved by adopting upstream's new,
Zero-Trust-aware ordering while keeping the manual-range override. The
resulting files differ from pure upstream only by the additive patch. The
baseline cache was repaired so the next upgrade has a real merge base.

## Findings

### Resolved in this release
* **M-1 (medium)** Organization secrets would have been world-readable via
  `/proc` if passed as CLI flags. Moved to the environment.
* **M-2 (medium)** Organization secrets would have been stored in plaintext in
  the DataStore protobuf. Moved to Keystore-sealed storage.
* **L-1 (low)** Free-form DNS / routing input could have injected extra engine
  arguments. Allow-listed and capped.
* **L-2 (low)** `CORE_VERSION` claimed 1.4 while the vendored sources were
  1.3.0, which silently defeated the merge-base logic. Corrected; the file is
  now written by the sync script from the tag it actually fetched.

### Open / accepted
* **A-1 (accepted)** The Keystore key is not user-authentication bound. Doing
  so would break unattended reconnects (boot, network change), which is the
  core function of a VPN. Documented in `SecretStore`.
* **A-2 (accepted)** The engine runs in-process with the app's own UID and
  reaches the network directly. This is inherent to a VpnService client.
* **A-3 (low, open)** The e-mail one-time-code flow shows engine output in the
  diagnostics log. The code is single-use and short-lived, but the log should
  redact it; tracked for the next release.
* **A-4 (informational)** Upstream v1.5.0 added `apifront.rs`, which
  deliberately presents legacy TLS fingerprints to blend in with censored
  networks. That is an anti-censorship measure, not a weakening of the tunnel:
  the tunnel's own cryptography (MASQUE/QUIC, WireGuard) is unaffected.

## Verified unchanged and still correct
* No component is exported except the launcher activity and the Quick Settings
  tile; the VPN service is protected by the platform permission.
* `network_security_config.xml` disables cleartext app-wide; the only
  exceptions are loopback endpoints for the local SOCKS5/HTTP bridge.
* `backup_rules.xml` excludes file, database and sharedpref domains, so
  nothing (including the sealed secrets) leaves the device via backup.
* Release builds are minified with ProGuard and signed from CI secrets; no
  keystore material is in the repository.
* Verbose engine output is gated behind `BuildConfig.DEBUG`.
