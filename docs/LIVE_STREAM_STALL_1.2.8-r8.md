# Upload stall - 1.2.8-r8 (root cause, with the r7 log as evidence)

Report: *"in Aether + Psiphon chained mode, when I upload, the ping goes over
2000 ms, then send and receive both stop, and I have to disconnect and reconnect
before the VPN works again."*

r7 is the first build whose log can answer this, because r7 is the build that
started reporting acknowledged bytes. It answers it, and the answer is not what
r2 through r7 were each looking for.

## What the r7 field log actually says

```text
19:59:49 [uplink 188.114.97.53:955] 1506 pkts, 1606 KB (107 KB/s)
                                    | kernel sndbuf 256 KB
                                    | writer waited 0 times, 0 ms total
19:59:51 [netstack] tcp flows=3 | backlog 0 bytes | socket send-queue 3792 bytes
                    | unacked-for 24.760847ms | device queue peak 2/64
                    | backpressure 0 | tail-drops 0 (session)
19:59:51 W/ping  first round trip 4803 ms, confirming 1309 ms, floor 134 ms
19:59:56 W/netstack flow 4 has 58404 bytes queued and has had NOTHING
                    acknowledged for 3.138011154s
20:00:02 I/netstack flow 4 is draining again after 9.40635077s
                    (61908 bytes still queued); it recovered on its own
20:00:03 W/ping  first round trip 7173 ms, confirming 456 ms, floor 134 ms
```

Three facts, all in the same ten seconds:

1. **The queue is real and it is seconds deep.** A 7173 ms round trip on a path
   whose session floor is 134 ms is 7 seconds of queueing, not latency.
2. **The queue is not in any place r5, r6 or r7 bounded.** The uplink writer
   never waited once (so the 128 KB kernel `SO_SNDBUF` r6 installed was never
   full), the device queue peaked at 2 of 64 packets, `backpressure` was 0 and
   session `tail-drops` were 0.
3. **The flow then went 9.4 s with zero bytes acknowledged** and self-recovered
   2.6 s inside r7's 12 s reset deadline - after which the session was
   *connected and useless*: uplink 6-18 KB/s for the next three minutes while
   the latency badge read a perfectly healthy 140-260 ms, until the user
   disconnected by hand at 20:03:12.

## Root cause: every queue on the upload path is bounded in BYTES, and one of
## them was never bounded or measured at all

The app-to-network path is:

```text
uploading app -> TUN -> hev-socks5-tunnel -> [loopback] -> PsiphonSocksFront
  -> [loopback] -> Psiphon SOCKS -> Psiphon SSH  (ONE connection, whole device)
  -> Aether SOCKS5 -> data_in channel -> TcpState::pending -> smoltcp send buffer
  -> StackDevice.tx -> WireGuard writer -> kernel UDP socket -> radio
```

with these limits before r8:

| queue                        | limit                       | at 107 KB/s |
|------------------------------|-----------------------------|-------------|
| `data_in` mpsc channel       | 1024 **messages** x <=16 KB | seconds, unmeasured |
| `TcpState::pending`          | `max_tcp_pending()` 256 KB  | 2.4 s, unmeasured |
| smoltcp send buffer          | `tcp_tx_buf()` 128 KB (r6)  | 1.2 s, measured (r6) |
| `Backlog`                    | `MAX_BACKLOG_BYTES` 512 KB  | 4.8 s, measured (r2) |
| loopback legs of the front   | Android autotuning, MBs     | seconds, unmeasured |

Every one of those is a byte count, and **a byte count is a latency bound only
if you know the rate**. Nothing in this codebase knew the rate. Add the three
netstack queues up and an upload may legitimately park ~900 KB - about **eight
seconds** at the rate this uplink sustained, which is the 7173 ms the ping
monitor measured, to the sample.

In chained mode that is fatal rather than untidy, for the reason r7 already
identified: Psiphon multiplexes the entire device over ONE SSH connection, so
those eight seconds sit in front of *everything* - the latency badge, DNS, every
other app. The upload does not slow itself down; it slows the phone down.

### Why six rounds of telemetry could not see it

Every gauge is a **point sample read at the end of a 15 second window**, and
`pending` plus the channel depth were never sampled at all. `socket send-queue
3792 bytes` and a 4803 ms probe are the same instant seen from two places: the
spike lives between samples. That is not a small reporting flaw, it is why r2's
loop rework, r5's backlog clamp, r6's two buffer clamps and r7's liveness signal
each landed on a queue that was already empty when it was read.

### Why it needs a manual reconnect

r7's reset deadline is 12 s and the stall was 9.4 s, so nothing fired. "It
recovered on its own" was then treated as a good outcome, and it is not: the
uplink never returned above 18 KB/s. The only two states the code knew were
"reset it after 12 s" and "do nothing", and the failure lived in between them.

## The fix

### 1. Per-flow uplink admission, measured in time (`netstack.rs`)

Every flow now carries a `FlowCredit`. The app side may only have

```text
outstanding = bytes handed to the stack - bytes the peer has acknowledged
```

in the pipe at once, and the ceiling is that flow's **own measured drain rate x
`UPLINK_QUEUE_BUDGET` (500 ms)**, clamped to `[48 KB, tcp_tx_buf()]`. Past it the
SOCKS5 reader does not read - which is backpressure that leaves this process,
crosses the loopback leg, reaches hev-socks5-tunnel, reaches the TUN, and ends up
in the congestion window of the app that is uploading. That is where it belongs,
and it is what every other VPN gets for free by not terminating TCP.

- The rate is derived from the same acknowledged-bytes signal r7 introduced,
  because it is the only rate here that a dead path cannot fake.
- The floor (48 KB) is above this path's bandwidth-delay product (107 KB/s x
  ~0.4 s = ~43 KB), so admission cannot become the throughput limit; it can only
  remove standing queue.
- The ceiling is `tcp_tx_buf()`, so r8 can never grant more queue than r6 already
  allowed. It is strictly a reduction.
- Same session, same rate: **~53 KB outstanding instead of ~900 KB**, i.e. ~0.5 s
  of standing queue instead of ~8 s.

This is deliberately not another buffer-size constant. r5 shrank the backlog, r6
shrank the kernel send buffer and the socket send buffer; each was correct and
each was defeated by the next queue down the line, because a fixed byte count
cannot know the rate.

### 2. The loopback legs are bounded too (`PsiphonSocksFront.kt`)

Backpressure has to land somewhere, and without this it lands in Android's
loopback autotuning - megabytes per socket, on the two legs this front sits on,
where no counter in this project can see it. `SO_SNDBUF`/`SO_RCVBUF` are pinned
to 64 KB per direction per leg (~0.6 s at the measured rate). This is the same
mistake r6 found on the WireGuard socket, in a different kernel buffer.

### 3. Stalls that keep happening are no longer survivable

- `TCP_DRAIN_STALL_TIMEOUT` 12 s -> **8 s**. The observed stall was 9.4 s: it
  would now be reset, and in chained mode a reset costs one automatic Psiphon
  redial instead of a manual disconnect and reconnect.
- New `STALL_FLAP_LIMIT`: **two** stalls of >=3 s inside 60 s reset the flow even
  if each one recovered by itself. A stall that self-recovers just under the
  deadline is a warning shot, not a recovery.

### 4. The telemetry now reports maxima, not samples

The `[netstack]` line gains:

```text
| uplink peaks: outstanding N B, pending N B, socket N B, budget N B
```

`outstanding` and `pending` have never been reported before, and all three are
**window high-water marks**, so a spike can no longer hide between two samples.
`budget` is what admission is currently enforcing, so the log states the rule it
is applying rather than leaving it to be inferred.

## Verifying r8 on device

1. Line 2 must read `AETHER-BUILD-STAMP:1.2.8-r8` and the stamp must end
   `uplink admission=rate-x-500ms`. Anything else is a pre-r8 engine.
2. The `[netstack]` line must carry `uplink peaks:`. If it does not, the engine
   is older than r8 whatever the APK says.
3. Start the upload that used to break it. `outstanding` should settle near
   `budget` (tens of KB) and **stay** there, `pending` should stay near zero, and
   the badge should stay within a few hundred ms of the session floor. A deep
   `socket` peak with `unacked-for` in the milliseconds is a healthy busy tunnel.
4. If the badge does spike, the same line now says whether the queue was ours:
   `outstanding` at the budget means the gate is holding and the delay is beyond
   this device; `outstanding` far above it would mean the gate is not in force,
   i.e. a pre-r8 engine.
5. If a path genuinely dies you get the r7 warning within 3 s and a reset by 8 s,
   or immediately on the second stall in a minute - and Psiphon redials on its
   own. No manual reconnect.

Compiling was not possible here (no internet, no Android SDK, no Rust
toolchain), so build through CI. Version stays **1.2.8** (`versionCode 12`); only
`PATCHLEVEL` moved, to `1.2.8-r8`.
