# 1.2.8-r4 — the actual root cause: the netstack had no congestion control

Version unchanged: **1.2.8**. Build tag `r4`.

## What r2 and r3 got wrong

Both were real bugs and both are still fixed. Neither was the cause.

* **r2** (queue starvation in the netstack `select!`) — real, fixed, and the
  starvation warning it shipped was a false positive that sent r3 chasing a ghost.
* **r3** (per-packet boringtun lock hand-off; watchdog probing TCP/53) — real, and
  **the watchdog fix is confirmed working by the new log**: at `10:41:22.321` the
  app says `probe did not answer but the data path is still carrying traffic - not
  a wedge, leaving the session alone`, and there is **no `Rebuilding the session`
  anywhere in six minutes**. The session was never killed again. The symptoms did
  not change at all, which is the proof that neither r2 nor r3 was the cause.

The reason five rounds missed it: **nothing was ever wrong with any of the parts
that were being examined, and nothing was logging the part that was.** See
"Telemetry" below.

## Root cause 1 — `NoControl`: the TCP sender had no congestion window

`native/aether/aether/Cargo.toml`:

```toml
smoltcp = { default-features = false, features = [... "socket-tcp" ...] }
```

`socket-tcp-cubic` is one of smoltcp's DEFAULT features. With
`default-features = false` and no re-enable, `AnyController::new()` resolves to
`NoControl` — not a conservative controller, the **absence** of one. No slow
start, no congestion window, no reduction on loss. And `netstack.rs` never called
`set_congestion_control`, so nothing overrode it.

Aether **terminates** TCP inside smoltcp. That means this stack, not the phone's
kernel, owns congestion control for everything the device uploads. That job was
assigned to nobody.

The collapse is self-reinforcing:

```
send at line rate -> bottleneck queue fills -> RTT climbs -> loss
  -> smoltcp retransmits without reducing anything (there is nothing to reduce)
  -> more queue, more loss -> flush_tx tail-drops at MAX_TX_RETAINED
  -> those drops are invisible to a controller that does not exist, so they are
     retransmitted too -> collapse, held indefinitely
```

Every asymmetry in the report falls out of this, exactly:

| Observation | Explanation |
|---|---|
| Live dubbing dies in the first second | It is a **sustained upload** (~44 KB/s of PCM). Almost nothing else the phone does uploads continuously. |
| Pure download looks fine | That direction is paced by the **remote server's** congestion control, which is a real one. |
| YouTube spikes and recovers | The player's request/ACK path uploads in bursts. |
| Press "stop" and the ping comes back *gradually* | The standing queue drains at link rate. Nothing had died; nothing had to recover; nothing logged an error because nothing was technically failing. |
| **Another VPN has no problem at all** | Other VPNs forward packets and leave congestion control to the phone's own CUBIC/BBR. Aether was the only stack in the path with an unthrottled sender. |

**Fix.** `socket-tcp-cubic` enabled in `Cargo.toml`, and
`socket.set_congestion_control(tcp::CongestionControl::Cubic)` set explicitly on
every flow in `netstack.rs`. CUBIC rather than Reno because this is a high-RTT,
high-loss mobile path — the regime Reno handles worst. Setting it per socket also
means a future feature-list edit **fails the build** instead of silently shipping
this a sixth time: without the feature, the enum variant does not exist.

## Root cause 2 — a 512 KB advertised receive window (bufferbloat)

`sysprofile.rs` sized rx and tx from the same number: 512 KB per flow on this
device. smoltcp derives the window it advertises from free space in the rx
buffer, so **512 KB was a licence for one remote server to keep half a megabyte
in flight** toward a path measured at 475 ms — roughly **two seconds of standing
queue** in the carrier's buffers, shared by every other flow on the device.

That is "the ping goes to 2000 and the connection drops and comes back", in the
download direction, with no upload involved at all.

**Fix.** rx and tx are separate settings now, with opposite requirements. tx keeps
its old sizing (it queues *for* CUBIC). rx is sized to a plausible mobile
bandwidth-delay product: **192 KB** on high tier (128 KB medium, 64 KB low) —
still ~3.8 Mbit/s per flow at 400 ms, no longer enough to hide seconds of latency.

## Root cause 3 — the scan rejected a fast endpoint and chose a slower one

From the new log, 2 ms apart:

```
[-] cached endpoint 162.159.192.115:859 answered but is slow
    (rtt 396.851077ms over the 180ms budget); ignoring the cache and
    scanning for a faster endpoint
[+] wg candidate ok 162.159.195.92:1701 rtt=475.011923ms
[+] selected WireGuard endpoint 162.159.195.92:1701
```

`WgScanMode::Turbo` sets `early_exit_first`, and `hunt_wg_endpoints` took it
literally: **the first candidate that answered was returned regardless of RTT** —
0.48 s into a 30 s budget, with 79 of 80 candidates unprobed. The RTT budget
guarded the *cache* and nothing guarded the *scan*, so the build discarded a
397 ms endpoint as too slow and committed the session to one **79 ms worse**,
while the DPI fingerprint in the same second had measured 104–115 ms edges on the
exact ranges being scanned. That endpoint is the one in both screenshots, and in
chained mode its RTT is paid twice.

**Fix.** The same budget now gates the scan, by construction: `AETHER_SCAN_GOOD_RTT_MS`,
falling back to the cache budget `AETHER_QUICK_RECONNECT_MAX_RTT_MS`, falling back
to 180 ms. An over-budget first answer is **kept as a fallback** while the scan
spends a short grace window (1.2 s) looking for a fast one; when it closes,
`distinct_by_ip` sorts by RTT so the **fastest** endpoint wins, never merely the
first to answer. This can never turn a working connect into a failure.

## Root cause 4 — the latency badge measured flow setup, and caused load

`Latency 5363 ms` in **a1**, while the same screen shows the tunnel moving
1.2 KB/s and 12.8 MB / 5.1 MB of session totals. `PingMonitor` opened a **new**
connection per measurement and reported how long it took. Chained, that is
local SOCKS5 handshake + Psiphon opening a **new SSH `direct-tcpip` channel across
both hops** + the remote TCP handshake. The channel open is the expensive term and
it queues behind everything else the tunnel carries. The badge was reporting
control-path congestion as the user's ping.

It was also *causing* it: one probe every 4 s is **~90 fresh SSH channels per
six-minute session, forever**. The log carries the bill —
`channel dial timeout: direct-tcpip` twice, plus `port forward failures for
qkhOScJt: 2`.

**Fix.** One warm TCP+TLS session through the tunnel, dialled once and kept alive.
A measurement is an HTTP keep-alive round trip on that existing connection: one
packet each way, **zero channel dials**. The dial cost is still recorded, as
`lastSetupMs`, where it belongs. The warm session is dropped on retarget,
rotation and teardown so a dead connection's timeout can never be shown as latency.

## Root cause 5 — green bars over the word "Fair" (screenshot a2, confirmed)

Two independent threshold tables that disagreed:

* colour: a continuous ramp between 80 ms and 400 ms, mint at `strength >= 0.66`,
  i.e. **any RTT up to 189 ms**;
* label: a separate ladder that called anything over **160 ms** `Fair`.

Every reading from 161–189 ms therefore rendered as green bars under "Fair".
**173 ms**, the exact value in a2, sits in that window (strength 0.709 → mint).
Neither number was wrong; there were two of them.

**Fix.** One `PingGrade` enum with one set of cut-offs (excellent ≤ 80, good ≤ 190,
fair ≤ 320, poor above) driving **both** the colour and the label. 173 ms now reads
`Good`. The smooth `strength` ramp still exists, but only for bar height, where a
continuous value is what an animation wants.

## Telemetry — why this took five rounds, fixed

In the last field log the engine's final line was at **second 51 of a six-minute
session**, and **393 of 478 lines** were Psiphon `updated server <id>`
bookkeeping. There was no data-plane visibility in the window where the problem
happens, and the 800-line ring was being consumed by noise.

* `netstack.rs` now emits one INFO line every 15 s: flow counts, app→net and
  net→app packet counts, backlog bytes, and **outbound tail-drops** per window and
  per session. That last counter is the direct fingerprint of an unthrottled
  sender — with CUBIC in place it should sit at or near zero.
* `PsiphonTransport.onDiagnosticMessage` drops four routine notice shapes
  (`updated server`, `discarding server`, `ServerEntryIterator.reset`, `Set dial
  parameters for`) and prints a `(+N routine server-list notices suppressed)`
  counter instead. `PsiphonHealth` is fed the **raw** stream before the filter, so
  the watchdog's evidence is unchanged.

## How to verify

1. Five minutes of live dubbing on chained mode. The badge should stay flat and
   plausible; there must be no `Rebuilding the session`.
2. In the log, check the new `[netstack] ... window:` lines. **`outbound
   tail-drops` should be at or near zero.** If that number climbs during dubbing,
   send the log — it means the send path is still being overdriven and the next
   step is `MAX_TX_RETAINED` / `packet_queue_capacity`, not more guessing.
3. Confirm the selected endpoint. If the first answer is slow you will now see
   `first wg answer ... is slow ... scanning 1.2s more for a faster edge`,
   followed by a faster selection.
4. a2's case: at ~173 ms the meter must read **Good**, not Fair.

Cannot be compiled here — the sandbox has no internet access and no Android SDK,
so build with your own CI. If `set_congestion_control` or
`tcp::CongestionControl::Cubic` is rejected, it is a smoltcp API-name mismatch and
a one-line fix; send the error.
