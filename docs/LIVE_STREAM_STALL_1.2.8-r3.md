# 1.2.8-r3 — the live-stream stall, actually found this time

Version stays **1.2.8**. Two independent faults, one on each side of the JNI line.
The r2 note in this folder is **wrong** and is superseded by this one; the section
"What r2 got wrong" explains exactly how, because the same mistake is easy to
repeat.

## 1. The app was destroying its own working session (the reconnect loop)

Everything below is from the r2 field log, verbatim.

```text
09:33:36.081 diag: device dns via socks5 udp = true
09:33:36.353 diag: dns+http OK, exit ip=146.59.70.6 cc=PL      <- real paths: fine
09:33:36.288 Psiphon: LocalProxyError ... ssh: rejected: administratively prohibited
...
09:34:39.090 ping: Latency probe failed (viaTunnel=true): Connect timed out
09:34:56.076 ping: Latency probe failed (viaTunnel=true): Connect timed out
09:34:56.150 vpn:  Aether -> Psiphon is up but carries nothing (1/2).
09:34:59.393 PsiphonHealth rotating off server n+WE7s6A - reconnect 1/4.
             The pipeline stays up; only the exit server changes.
09:34:59.396 PsiphonSocksFront udpgw session closed
09:35:00.676 PsiphonSocksFront this server does not allow outbound TCP/53
09:35:03.297 vpn:  Aether -> Psiphon is up but carries nothing (2/2).
09:35:03.298 vpn:  Rebuilding the session: the data path is wedged.
09:35:03.331 tunnel: hev-socks5-tunnel stop requested
```

Read it in order and the whole "ping spikes, it drops, it comes back, it repeats"
report falls out of it:

**a. The watchdog probe used a port the exit refuses.** `probeTunnelOnce()`
dialled `1.1.1.1:53`, `1.0.0.1:53`, `9.9.9.9:53` — DNS over **TCP/53**. A large
share of Psiphon exits refuse TCP/53 outright, and the app's own front says so at
09:35:00.676. So on those exits *every* watchdog check failed forever, on a
perfectly healthy tunnel. The contradiction is 270 ms wide in the log: the
connectivity self-test passes over the paths real traffic uses at 09:33:36.353,
and the probe is refused at 09:33:36.288. `PingMonitor` had the same bug, which is
why the badge on screen said "timed out" while pages were loading.

**b. A failed probe was allowed to convict the data path on its own.** At
09:35:03 the session was torn down as "wedged" while Psiphon's own counter for
that same tunnel read `received: 703150, sent: 385706`. A path moving 700 KB is
not wedged. One probe destination is an opinion; the tunnel core's byte counters
are a fact, and nobody was reading them.

**c. The two watchdogs fought, and the one holding the axe won.** 09:34:59.393
starts a *controlled* exit rotation — "the pipeline stays up; only the exit server
changes". It needs a few seconds during which nothing can be dialled, by design,
and `PsiphonTransport.isAlive()` already masks that window. The pipeline probe did
not, so 3.9 s later it counted the repair as the failure and killed the whole
session: hev down, Psiphon down, stage 1 down, ~10 s of dead air, then a fresh
bootstrap that walks into the same sequence again. On a loop.

**d. Every one of those events kills long-lived flows.** The rotation drops the
udpgw association (09:34:59.396), so every UDP/QUIC flow on the device dies at
once; the rebuild kills every TCP flow too. A page reload survives that. A live
dubbing session, a video, a WebSocket does not — which is why the symptom looked
app-specific when the cause was not.

### Fixes

- Probes moved to **port 443** and made a real TLS handshake (ClientHello out,
  ServerHello + certificate + Finished back). Still a genuine payload round trip,
  now over the one port an exit cannot refuse and still be an exit.
- Probe destinations are registered with `PsiphonHealth.registerSelfProbe()` and
  skipped by `onDestinationRefused()`. The app's own traffic can never again be
  evidence about a server, whatever port it lands on.
- Both watchdogs (chained pipeline and Aether-only engine) now require the tunnel
  core's cumulative byte counters to be **flat** before a probe failure counts:
  `dataPathIsMoving()` / `DATA_PATH_ALIVE_BYTES`.
- The pipeline probe stands down while `PsiphonHealth.isSettling()`, so a rotation
  is allowed to finish instead of being executed mid-repair.
- The latency badge tries three endpoints before reporting an error.

## 2. The engine serialised uploads against downloads, per packet

`wireguard.rs`, send task. It was, per packet:

```text
outbound_rx.recv().await  ->  tunn_w.lock().await  ->  sock.send().await
```

`tunn` is the single boringtun session, and the socket reader takes the **same**
lock for every datagram it decapsulates. One acquisition per packet, both
directions, against each other, on a fair async mutex — so every hand-off is a
full task park and wake through the tokio scheduler.

On an **asymmetric** flow that costs almost nothing: a download keeps the reader
busy and the writer barely competes. On a **symmetric** flow the two tasks trade
the lock on literally every packet, the uplink cannot drain faster than the
scheduler round-trips, the queue in front of it grows, and RTT walks up into
seconds while the tunnel is healthy and still downloading.

That is the reported shape and nothing else is:

- live dubbing is permanently symmetric (~44 KB/s up while ~64 KB/s comes down) —
  it degrades within a second of pressing start;
- a download or a normal page of the same volume is not — it is fine;
- press stop and the contention disappears, so RTT walks back **down gradually**
  rather than snapping back: nothing had died;
- another VPN takes one lock per packet too, but has no second task fighting it
  for the same session on every packet.

There was also a second async mutex on that hot path, `obf_sent`, taken for every
outbound datagram forever to re-read a flag that can only change once.

### Fixes

- Outbound packets are drained in bursts and a burst is encapsulated under **one**
  lock acquisition (`MAX_ENCAP_BATCH = 64`), so hand-offs scale with bursts, not
  packets. No packet waits to build a batch — the burst is whatever is already
  queued when the first packet arrives.
- `obf_sent` is consulted once per session instead of once per packet.

## What r2 got wrong

r2 blamed a `biased` `select!` in the netstack scheduler for starving the upload
direction, and shipped telemetry to prove it. The telemetry then fired constantly:

```text
[netstack] app->network idle for 32.033894156s while 1 packets arrived;
           backlog 0 bytes over 0 blocked flows
```

That line is arithmetically impossible for the fault it claims. One inbound packet
per pass is not a saturated download, and an empty backlog over zero blocked flows
means nothing was waiting to go out at all. It was a **false positive with two
causes**, both now fixed in `netstack.rs`:

1. the `select!` arm that receives `data_in_rx` moves app→network data but never
   refreshed `last_app_progress`. On any tunnel that is not saturated every
   wake-up arrives through that arm, it empties the queue, and the drains at the
   top of the next pass therefore score `moved_app = 0` — so the timer aged
   forever on a session that was working;
2. the trigger was `moved_in > 0`, i.e. one packet.

The report now requires the download direction to actually be busy
(`STARVATION_MIN_INBOUND = 32`) **and** a non-empty backlog. If it ever fires
again it means something.

The r2 scheduler rewrite itself is kept: per-queue budgets and a rotating visit
order are correct regardless, and the diagnosis being wrong does not make the code
worse. But it fixed nothing the user could feel, and this document exists so the
next reader does not spend another round chasing that ghost.

## How to verify

1. Connect on `Aether -> Psiphon`, start the dubbing app and leave it running for
   five minutes. Expect: no `Rebuilding the session`, no `up but carries nothing`,
   and the RTT flat instead of climbing.
2. On an exit that refuses TCP/53, confirm the log still prints
   `this server does not allow outbound TCP/53` (informational) but **no** matching
   `refused N different destinations` conviction from it.
3. If a probe does fail while traffic is flowing, expect the new line
   `probe did not answer but the data path is still carrying traffic` and no
   teardown.
