# Third-party notices

AetherMobile is distributed under AGPL-3.0-or-later. This release includes or builds against:

- Psiphon Tunnel Core Android 2.0.39. Its AAR and native components retain their upstream licenses and notices.
- hev-socks5-tunnel, Apache-2.0 / MIT.

Do not remove upstream copyright or license files when redistributing binaries or modified source. AGPL source-offer obligations still apply to distributed builds and network deployments.

## Psiphon (1.4.0)
psiphon-tunnel-core (GPL-3.0), built from source by the engine's
`psiphon-build.sh` and shipped as `libpsiphon.so`. The previously bundled
`psiphontunnel` AAR is no longer included.

## Custom configs (1.5.0)
- Xray-core v25.12.8 (MPL-2.0), https://github.com/XTLS/Xray-core. The official
  release binaries are shipped unmodified as `libxray.so`; their source is
  available from the upstream repository at tag `v25.12.8`.
- ZXing core 3.5.3 and zxing-android-embedded 4.3.0 (Apache-2.0), QR code
  scanning and decoding.
- kotlinx.serialization-json 1.7.3 (Apache-2.0).
- The NapsternetV `.npvt` decoder (white-box AES-CTR tables in
  `app/src/main/assets/npvt/wb.bin`) is an independent Kotlin implementation
  for interoperability with files users already own.

## Tor directory and channel crates (1.5.0)
- `tor-chanmgr` 0.46.0 and `tor-dirclient` 0.46.0 from Arti (MIT OR Apache-2.0),
  https://gitlab.torproject.org/tpo/core/arti, vendored in
  `native/aether/vendor/` with small changes marked `AETHER-VENDOR-PATCH`
  (relay handshake without SNI behind an upstream proxy; directory timeouts
  for slow chains). Arti's LICENSE-MIT and LICENSE-APACHE are included in each
  vendored crate.
