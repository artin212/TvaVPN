# Transport backends (1.2.7)

`AetherVpnService` is the only Android VPN owner. A backend opens SOCKS5 on
loopback; the existing TUN and hev forwarding layer consume it. This prevents
nested VPN services, route loops, and inconsistent kill-switch behavior.

Plain Aether listens on `127.0.0.1:1819` exactly as before. The chained backend
puts the Aether engine on `1819` and the second stage on `127.0.0.1:1825`
(`TunnelConfig.CHAIN_SOCKS_PORT`); the service follows the port the transport
reports rather than demanding a fixed one.

Two backends ship: **Aether** and **Aether -> Psiphon**. The single-hop Psiphon
and Tor modes were removed in 1.2.7, and so was the Tor backend altogether - see
"Removed: Tor" at the end of this document.

## Aether
Existing MASQUE, WireGuard, and Gool behavior is unchanged. WARP is anycast, so
an arbitrary exit country cannot be promised.

## Psiphon
`PsiphonTransport` starts Tunnel Core in local SOCKS mode. The selected ISO
country is passed as `EgressRegion`, which is a hard availability filter in
Psiphon: if no server is reachable in that country the controller hunts for an
egress that will never appear. The transport therefore makes a second attempt
with the filter removed and a fresh datastore before it reports failure.

Psiphon's datastore now lives in `filesDir/psiphon` instead of `filesDir` root,
so it can be reset independently of `hev.yaml`. The local
HTTP proxy is disabled (`DisableLocalHTTPProxy`); nothing in this app uses it and
one fewer listener is one fewer port clash.

## Chained backend: `Aether -> Psiphon`

Why: Aether/WARP exits are anycast Cloudflare edges. A significant set of
destinations either block them outright or serve a different view of the internet
through them, so the tunnel is up and sites still do not open. Chaining swaps the
*exit* for a Psiphon address while keeping Aether's obfuscated transport on the
*first* hop, which is the hop that has to survive the local network.

Order of operations (`AetherVpnService.connectExternal`):

```
stage 1   Aether engine   -> SOCKS5 127.0.0.1:1819         no TUN yet
stage 2   Psiphon          -> SOCKS5 127.0.0.1:1825         dials out via 1819
then      TUN + tun2socks  -> 127.0.0.1:1825                exit = Psiphon
```

* Stage 1 reuses the existing Smart Auto and hand-picked ladders unchanged, so a
  chained session gets the same DPI fingerprinting, hardening and retries as a
  plain Aether session.
* Stage 1 must not build the TUN: stage 2 reaches the engine over loopback while
  the engine still reaches the internet over the real network. Once the TUN is
  up, `applyAppFilter` excludes this package, which is the same loop prevention
  plain Aether already relies on.
* Stage 1 is gated on `Diagnostics.runProxyStage` (port, SOCKS5 handshake, real
  outbound TCP) and deliberately not on the full self-test: the exit, the DNS and
  the flag all belong to stage 2, and running a geo lookup at stage 1 would both
  slow every connect and paint the *first* hop's country into the badge.
* How the upstream is expressed: Psiphon gets `UpstreamProxyUrl`. It routes every
  connection it makes (including directory fetches and server-list fetches)
  through it, so a chain cannot leak a direct dial.
* Both hops are supervised. If stage 1 dies, the session is rebuilt rather than
  left "connected" over a proxy that cannot dial.

Expect a slower connect than plain Aether, since a chained session pays both
hops' warm-up. The self-test grace window for chained sessions is 150 s
(`Diagnostics.EXTERNAL_GRACE_MS`) instead of the 90 s used for plain Aether,
because failing a session that is merely slow is the worst possible outcome.

## Exit country list
`ExitRegions` is Psiphon's published egress regions. (It used to be the union of
those and the countries Tor had reliable exits in; the codes are unchanged after
the Tor removal, so a saved exit country still resolves.) Labels carry the flag
emoji, derived
from the ISO 3166-1 alpha-2 code via `NetProbe.flagEmoji` (regional-indicator
pairs rendered by the system emoji font) rather than a shipped image set, so the
list stays in sync with the codes and costs no APK size. "Automatic" is marked
with a globe.

## Native build
There is no transport-specific native step left. Psiphon ships as the prebuilt
AAR in `app/libs/`, and the Aether core is built by `scripts/build-natives.sh`.
`scripts/build-tor.sh` and the release-CI step that ran it were deleted with the
Tor backend.

## Limits
Country availability depends on the live network. Psiphon cannot guarantee every
country at every moment, which is why a pinned region falls back to an automatic
exit rather than hanging.

## Removed: Tor (1.2.7)
The Tor backend and the chained `Aether -> Tor` mode are gone, along with
`TorTransport`, `TorSocksFront`, `scripts/build-tor.sh`, the packaged `libtor.so`
and the `TOR_SOCKS_PORT` / `TOR_DNS_PORT` constants (1822 / 1823).

Tor carries TCP streams only. Every datagram the device sent had to be answered
out of Tor's own `DNSPort` and everything else dropped, so QUIC never worked and
name resolution depended on a front proxy faking a SOCKS5 command Tor does not
implement. `Aether -> Tor` also paid a full Tor bootstrap *through* the Aether
handshake, which was slow enough that users read a working connect as a hang.
Psiphon provides the same thing - a foreign exit behind Aether's obfuscated first
hop - carries real UDP through its remote udpgw intercept, and comes up in
seconds.

Saved profiles: `TOR` and `AETHER_TOR` resolve to plain `AETHER` in
`TransportBackend.fromStoredName`, deliberately NOT to `AETHER_PSIPHON`. A profile
that asked for a Tor exit is not silently re-routed through another provider's
network.
