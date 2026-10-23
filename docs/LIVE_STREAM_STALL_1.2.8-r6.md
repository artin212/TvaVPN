# Live-stream stall — 1.2.8-r6

**Version unchanged: 1.2.8. Patch level `1.2.8-r6`.**

This round is different from the previous five in one important way: **the build
under test was the right one.** The r5 provenance work did its job, and that is
what finally made the data readable.

---

## 1. The r5 build really ran, and the r5 fix really was not the bug

Lines 5, 9 and 10 of `loge.txt`:

```text
16:17:54.278 I/build: APK patch level 1.2.8-r5 (version 1.2.8, code 12003, core 1.8.0)
16:17:54.308 D/engine: [*] AETHER-BUILD-STAMP:1.2.8-r5 (app patch level 1.2.8-r5;
                       netstack congestion control=cubic, device backpressure=on)
16:17:54.309 I/build: Engine patch level 1.2.8-r5 matches the APK.
```

So every r5 conclusion can now be tested against the field, and **all of them
came back negative**. The data plane reported itself 96 times over 13 minutes and
every single line says the same thing:

```text
backlog 0 bytes over 0 flows | device queue peak 1/64 pkts, backpressure 0
| outbound tail-drops 0 (this window) / 0 (session)
```

* `backlog 0` — nothing was ever waiting for a socket.
* `tail-drops 0` — nothing was ever shed. The r5 root cause is genuinely gone.
* **`backpressure 0` — `transmit()` never refused once. Not once, in 13 minutes.**
* `device queue peak` reached 36/64 at its worst and sat at 1–3 the rest of the time.
* No starvation warnings, so r2's fairness fix is holding.
* `PsiphonHealth` never fired, so r3's watchdog fixes are holding: the session was
  never torn down by the app.

And yet, on that same session, at 16:29:50:

```text
16:29:50.498 I/ping: Warm latency session up via 1.1.1.1:443: setup 5694 ms,
                     round trip 3092 ms
```

Screenshot `a1` is that second: **Latency 3092 ms · Poor**, connected 00:11:29,
**up 49.6 KB/s, down 1.2 KB/s**, 38.5 MB down / 5.4 MB up total.

A path with **seconds** of standing queue, and a stack whose every queue gauge
reads zero. That contradiction is the whole answer.

---

## 2. Where the queue actually was

The uplink, end to end, with the depth of every queue on it:

```text
  Doble-Parsi-Mobile  (~48 KB/s of raw PCM, continuous, never backs off)
    -> TUN (mtu 1280)
      -> hev-socks5-tunnel
        -> PsiphonSocksFront            32 KB relay buffer
          -> Psiphon SSH  ONE TCP connection carrying the whole device
            -> aether socks.rs
              -> smoltcp socket tx buffer      512 KB   <- r4/r5 sized this
                -> StackDevice.tx               64 pkt  <- r5 gates transmit() here
                  -> outbound mpsc             256 pkt  <- r2 bounded this
                    -> WireGuard encap
                      -> UDP socket SO_SNDBUF    7 MB   <- nobody ever looked
                        -> qdisc -> radio
```

`send()` on a datagram socket only blocks when `SO_SNDBUF` is full. **At 7 MB it
is never full.** So:

* the WireGuard writer never waited,
* therefore the `outbound` mpsc never filled,
* therefore `flush_tx` never retained,
* therefore `StackDevice.tx` never reached `MAX_DEVICE_TX`,
* therefore `transmit()` never refused,
* therefore **CUBIC was still being shown a link with infinite capacity and zero
  loss** — precisely the condition r5 was written to eliminate, one layer below
  where r5 looked.

`backpressure 0` was never health. It was the receipt for a severed chain.

### Where the 7 MB came from

`upstream::tune_udp_buffers`, added by the **1.2.8 media-stall fix**. That fix was
correct about the receive side: WireGuard sockets were running on the OS default
`SO_RCVBUF`, the kernel was dropping inbound datagrams including handshake
replies, and raising it fixed a real bug. But it set **both** directions from one
figure:

```rust
let want = crate::sysprofile::udp_socket_buf_bytes();   // 7 MB on a high tier
let _ = reference.set_recv_buffer_size(want);
let _ = reference.set_send_buffer_size(want);           // <-- the bug
```

A 7 MB receive buffer only ever prevents loss. A 7 MB send buffer is an unbounded
queue in kernel space directly downstream of every throttle in the process. In
gool mode there are **two** of them in series: the loopback socket bridging the
inner tunnel to the outer one, and the wire socket. Both got 7 MB.

At the ~48 KB/s this app uploads, 7 MB is over two minutes of audio.

---

## 3. Why that produces exactly the reported symptom

**The ping.** Queueing delay is added to everything sharing the queue. A fresh dial
cost 5694 ms and 4496 ms during the event; 35 seconds later, once the stream had
quietened, the same dial to the same target cost **327 ms** and the round trip was
**191 ms**. Nothing was broken. The path's real RTT never moved.

**The download collapse.** This is the part that only makes sense once the queue is
located. In chained mode the netstack reports `tcp flows=1`: the entire device
rides **one** Psiphon SSH TCP connection. The ACKs that clock the download share
the uplink with the PCM upload. Push seconds of PCM into a queue in front of the
radio and the ACKs queue behind it, so the download's throughput — `rwnd / RTT` —
collapses while the upload keeps its 49.6 KB/s. That is the screenshot, exactly:
**49.6 KB/s up, 1.2 KB/s down.**

**Why it recovers on its own.** Nothing died, so nothing needed to reconnect. The
queue drains, the RTT walks back down. Which is what the user has described every
single round.

**Why five rounds missed it.** Every counter added in r2–r5 measured a queue inside
this process. The queue was in the kernel. The instrumentation was honest and it
was pointed at the wrong side of a syscall.

---

## 4. What changed in r6

### 4.1 The root cause: send and receive buffers are now separate budgets

`sysprofile` splits `udp_socket_buf` into `udp_socket_rcv_buf` and
`udp_socket_snd_buf`:

| tier   | `SO_RCVBUF` | `SO_SNDBUF` (was) | `SO_SNDBUF` (now) |
|--------|-------------|-------------------|-------------------|
| Low    | 256 KB      | 256 KB            | **48 KB**         |
| Medium | 2 MB        | 2 MB              | **96 KB**         |
| High   | 7 MB        | 7 MB              | **128 KB**        |

The receive side is untouched — that is the half of the 1.2.8 fix that was right.
The send side is now a **latency budget**: 128 KB is ~100 tunnel MTUs, roughly
250 ms of a 4 Mbit/s mobile uplink. Small enough that the socket pushes back,
large enough to absorb a whole 64-packet encapsulation burst without a wake-up.

Linux doubles `SO_SNDBUF` and reports the doubled value, so ~256 KB in the log is
correct and expected. A kernel that refuses to shrink it now **logs a WARN**,
because silently restoring this bug is not allowed to be an option.

### 4.2 The writer now propagates the wait, and measures it

`sock.send().await` is replaced with `try_send()` + `writable().await`, so a full
uplink is a *timed, counted* wait instead of an invisible one. With the buffer
bounded, that wait travels back up the mpsc → device queue → `transmit()` → CUBIC.
The chain r2/r4/r5 built is finally connected to something.

New log line, once per 15 s per tunnel:

```text
[uplink 162.159.192.1:2408] 15.0s window: 812 pkts, 1014 KB (67 KB/s)
  | kernel sndbuf 256 KB | writer waited 96 times, 412 ms total, worst 31 ms
```

### 4.3 The netstack TCP send buffer came down with it

512 KB → **128 KB** on the high tier. 512 KB in front of a single flow is ~10 s of
a 48 KB/s real-time stream, and in chained mode there is only one flow. At a
~600 ms path RTT, 128 KB still supports ~210 KB/s per flow, which is far more than
this chain delivers.

### 4.4 The telemetry finally measures the right queue

The netstack line gains `socket send-queue N bytes (worst flow M)`: what smoltcp
has *accepted and not yet put on the wire*. `backlog` (always 0) is what has not
been handed to a socket yet. Reading the two together distinguishes "nothing to
send" from "cannot send" — a distinction r5's telemetry could not make, which is
why its output looked healthy.

### 4.5 The latency badge no longer reports its own dial

The first round trip on a **just re-warmed** session rides a connection whose
handshake was itself queued. r6 takes a confirming sample on the established
session and reports the lower of the two, logging both. If the path really is at
three seconds, both samples say so. If it was only the dial, the second is clean.
The gap between them is the uplink queue depth and is now in the log.

### 4.6 A second time bomb, same shape as the one r5 found

`sync-core.sh` discovers app patches from `AETHER-APP-PATCH` markers and
`rm -rf`s everything else on a core upgrade. **`upstream.rs`, `quic.rs` and
`wireguard.rs` had no markers.** The r6 fix lives in all three, so on the first
upstream release our own CI would have deleted it with a green build — exactly the
failure r5 caught for `netstack.rs` and `Cargo.toml`. All three files are now
wrapped and therefore discovered.

---

## 5. How to read the next log

1. **Line 2 must say `AETHER-BUILD-STAMP:1.2.8-r6`.** If it does not, stop; nothing
   else in the log is about this build.
2. **The profile line must say `udp socket rcv/snd=7168KB/128KB`.** One combined
   `udp socket buffer=` figure means a pre-r6 engine.
3. Start dubbing, then look for `[uplink ...]`:
   * `writer waited` climbing while dubbing runs = **the fix is working.** That
     wait is the backpressure that was missing.
   * `writer waited 0` while the uplink is saturated and the ping is climbing =
     the kernel ignored the buffer request; the WARN in §4.1 will say so.
4. In `[netstack]`: `backpressure` should now be **non-zero** under load, and
   `outbound tail-drops` must stay **0**. Non-zero backpressure with zero
   tail-drops is a correctly throttled uplink.
5. `socket send-queue` should stay in the tens of KB. Hundreds of KB sustained
   means the controller is still not reacting.
6. The badge: `first round trip` and `confirming round trip` should be close. A
   large gap is queue, not latency.

Compiling was not possible here (the sandbox has neither internet nor the Android
SDK), so build through CI, which asserts the stamp inside the packaged APK.
