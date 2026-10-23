# Live-stream / dubbing stall - 1.2.8-r7

## The r6 build was correct, tested, and fixed a non-problem

The field log is stamped `AETHER-BUILD-STAMP:1.2.8-r7`-predecessor `r6` and the
profile line reads `udp socket rcv/snd=7168KB/128KB`, so r6's uplink clamp was
genuinely live. It also worked: the kernel honoured it (`kernel sndbuf 256 KB`
is Linux's usual 2x bookkeeping of a 128 KB request), no `WARN` fired, and

    writer waited 0 times, 0 ms total, worst 0 ms

held for every 15 s window while the uplink peaked at **146 KB/s**. The uplink
socket was never the bottleneck. r6 fixed a real 7 MB bufferbloat bug that was
not the bug the user is reporting.

## The number that gave it away

r6 added `socket send-queue`, and that is the one gauge that finally saw the
fault. Worst flow, same session:

| time     | socket send-queue | app->net | net->app |
|----------|-------------------|----------|----------|
| 18:13:15 | 73 120 B          | 30       | 695      |
| 18:13:45 | 121 200 B         | 6        | 66       |
| 18:14:00 | **131 072 B**     | **1**    | 19       |

131 072 is *exactly* the 128 KB TCP tx buffer: 100% full. A flow holding a full
send buffer emitted **one packet in fifteen seconds**, with `backlog 0`,
`backpressure 0`, `tail-drops 0`, `device queue peak 1/64`, and not one warning
anywhere in the log.

## Root cause: liveness was measured on the wrong event

`TcpState::last_progress` and `reap_wedged` are the only stall detectors in the
netstack, and both are blind to this:

```rust
if st.pending.is_empty() {
    st.last_progress = Instant::now();      // nothing waiting
} else if socket.can_send() {
    let sent = socket.send_slice(&st.pending)...;
    if sent > 0 { st.last_progress = Instant::now(); }   // <-- "progress"
}
```

`last_progress` is refreshed when the socket **accepts** bytes into its send
buffer. That is not progress, it is the socket agreeing to *remember* the bytes.
A flow whose peer has gone silent keeps accepting, so it keeps looking healthy
until the buffer is 100% full - and `reap_wedged` additionally only ever looked
at flows in `backlog.blocked`, which such a flow never enters.

The 30 s wedge clock therefore only started at ~18:14:00. It would have fired at
18:14:30. **The session ended at 18:14:16.** The dead flow was never reaped, and
never logged, in any of six rounds.

Every counter r2-r5 added measured a queue *inside this process*, and r6 moved
one syscall outwards. All of them were empty for the same reason: the bytes were
parked in a socket send buffer, and nothing in the codebase was allowed to call
that a queue.

### Why this destroys a chained session specifically

In `WIREGUARD -> PSIPHON`, Psiphon multiplexes the **entire device** over one SSH
connection, i.e. one netstack TCP flow. So one wedged flow is every app on the
phone, and the symptoms match a1 exactly: download collapses (`123 B/s`) while
upload keeps trickling out on keepalives (`1.2 KB/s`), and the latency badge
reports the depth of the stuck queue as ping (`6442 ms`).

The trigger in this session was stage 2: exit `dPIPMURu` began refusing
destinations (`administratively prohibited`), and `PsiphonHealth` correctly
rotated off it at 18:13:58 after 6 distinct refusals. That rotation is right.
What is wrong is that stage 1 held a dead flow open across the whole event
instead of resetting it and letting the app retry in seconds.

## The fix

**Liveness is now measured on acknowledged bytes.** Bytes only leave a TCP send
buffer when the far end acknowledges them, so `accepted_total - send_queue()` is
a monotonic count of what the peer really received - the only signal in this
stack that cannot confuse "sent" with "remembered".

- `TCP_DRAIN_STALL_WARN = 3 s` - a non-draining flow is reported immediately, so
  this failure mode can never be invisible in a log again.
- `TCP_DRAIN_STALL_TIMEOUT = 12 s` - then the flow is RST so the app is told now.
  Sized against this session's measured RTT (142-238 ms, endpoints 380-520 ms):
  12 s is ~25 round trips and ~5 RTO backoffs. Not slow, gone.
- `reap_wedged` scans **all** flows for this, before the `backlog.blocked` early
  return that made the case unreachable.

A naive "did `send_queue()` go down" check was written first and **rejected**: a
saturated upload sits pinned at the buffer limit and reads exactly 131072 on
every pass while draining perfectly, so it would have reset the healthiest flow
on the device. Acked-bytes has no such false positive.

**New telemetry** on the `[netstack]` line:

    unacked-for 4.1s (worst flow), 1 flow(s) not draining

This is the number that separates a deep queue that is moving from a deep queue
that is dead - r6's `socket send-queue` prints an identical value for both.

**Latency badge.** r6 fixed the sample taken after a re-warm and left the
steady-state sample - the one the badge shows nearly always - unfiltered *and
completely unlogged*. That is why the log's only latency numbers were 142/238/237
ms while the screenshot said 6442: the badge's actual source wrote nothing
anywhere. It now tracks a per-session floor, re-checks an outlier once
(>3x floor **and** >400 ms above it) and reports the lower sample, and logs every
reading with the floor beside it.

## Verifying r7 on device

1. Line 2 must read `AETHER-BUILD-STAMP:1.2.8-r7` and the stamp must end
   `flow liveness=acked-bytes`. Anything else is a pre-r7 engine.
2. The `[netstack]` line must carry `unacked-for`. If it does not, the engine is
   older than r7 regardless of what the APK says.
3. During dubbing, `unacked-for` should stay within a couple of RTTs (well under
   1 s) however deep `socket send-queue` gets. That is a healthy busy tunnel.
4. If a path dies you should now see, within 3 s:
   `flow N has NNNNN bytes queued and has had NOTHING acknowledged for ...`
   and a reset by 12 s - instead of a minute of silence.
5. `writer waited` staying at 0 is now expected and fine; the uplink was never
   the constraint.

Compiling was not possible here (the sandbox has no internet and no Android
SDK/Rust toolchain), so build via CI. Version stays **1.2.8** (`versionCode 12`);
only `PATCHLEVEL` moved to `1.2.8-r7`.
