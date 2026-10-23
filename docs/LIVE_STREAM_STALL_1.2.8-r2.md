# `Aether v1.2.8` — live two-way streams stall the tunnel: root cause and fix (1.2.8-r2)

Version stays **1.2.8**. This is a fix pass inside it, marked `1.2.8-r2` in code
comments.

## The report

1. Mobile: connect (`Aether → Psiphon`, or plain `Aether`) — everything works,
   ping is fine. Start **Doble-Parsi-Mobile 1.0.0** (live AI dubbing via Gemini
   Live) and the moment "start dubbing" is pressed the ping goes to ~2000 ms and
   no data moves at all. Press stop and the ping comes back **gradually**.
   Log: `loge1`.
2. Same thing on mobile data, slightly milder but still unusable.
3. Laptop over the shared VPN, `doble-parsi-chrome` extension: identical.
   Log: `loge2`.
4. Generally: any site or YouTube video drives the ping up and the connection
   drops for a moment, over and over.
5. With a different VPN the dubbing runs fast and none of this happens.

## What the logs prove

### A. The tunnel was not broken. It was one-way.

`loge1`, one 18-minute session, Psiphon's own counters:

```
21:23:29  TotalBytesTransferred: received 115,608,970   sent 6,071,080
```

**19:1.** A live dubbing session is symmetric by construction: it uploads ~44 KB/s
of 16 kHz PCM (100 ms chunks, base64 in a WebSocket frame) while it downloads
~64 KB/s of dubbed 24 kHz PCM. Download ran at ~107 KB/s the whole time. Upload
averaged 5.6 KB/s. Nothing in the log reports an error, because nothing failed —
the app→network direction was simply never scheduled.

The two other fingerprints match exactly:

* `W/ping: Latency probe failed (viaTunnel=true): Connect timed out` — eight
  times — while 100 KB/s of download was flowing. The probe is a *new* SOCKS5
  connection, so it needs the two starved paths (open-a-flow, then write to it).
* `W/vpn: Aether → Psiphon is up but carries nothing (1/2)` … `is carrying
  traffic again` — twice, self-healing. That is congestion clearing, not a tunnel
  dying.
* Recovery is *gradual* after "stop dubbing". A dead tunnel does not recover
  gradually; a starved queue does, as soon as the thing starving it stops.

### B. The udpgw port forward was being re-dialled 219 times in 18 minutes

`loge1`: 219 × `udpgw stream up`, 220 × `udpgw session closed`, up to **48 per
minute** under load, median session lifetime **0.15 s** and a first quartile of
**1 ms**. And the giveaway: of the 218 consecutive pairs of `stream up` lines,
**218 alternate lane**:

```
+bulk  -  +dns  -  -  +bulk  +dns  -  +bulk  -  -  +dns  -  +bulk  -  +dns ...
```

Two independent, merely congested streams cannot alternate perfectly 218 times
in a row. Two *mutually exclusive* ones cannot do anything else. `loge2` shows
the same thing: 203 of 203.

## Root cause

Three defects, in the order they hurt.

### 1. `biased` select starved the upload path — `netstack.rs`

The netstack's run loop ran one `tokio::select!` per pass, and it was `biased`,
which means the arms are polled strictly in declaration order:

```rust
tokio::select! {
    biased;
    maybe = inbound_rx.recv()  => { … }   // network → app  (downloads)
    maybe = cmd_rx.recv()      => { … }   // open a flow, resolve a name
    maybe = data_in_rx.recv(), if … => { … }   // app → network  (ALL uploads)
}
```

Arm 2 is only polled when arm 1 is not ready. Any sustained download keeps
`inbound_rx` non-empty at all times — the WireGuard reader refills it faster than
one pass drains it — so **arm 1 was ready on every pass and arms 2 and 3 were
never reached**. Uploads and new-flow setup were starved for exactly as long as
data kept arriving. One `biased` keyword, and the whole report:

* a permanently two-way flow (live dubbing, a call, a WebSocket) has its own
  download half keeping the loop busy, so its upload half never gets a turn:
  the WebSocket write queue fills, Gemini stops getting audio, RTT explodes;
* a video does the same to its ACK/upload path — hence the same symptom on plain
  YouTube, in both plain and chained mode, which is what proved the shared data
  plane was to blame rather than anything Psiphon adds;
* new flows could not be opened while an old one downloaded — "the ping is 2000
  and nothing else opens";
* nothing logged anything, because nothing failed.

### 2. Two udpgw port forwards, and a tunnel that only holds one — `PsiphonSocksFront.kt`

1.2.7-r3 gave DNS its own udpgw stream so a video could not head-of-line block a
name lookup in the kernel send buffer. Sound reasoning, fatal in practice: a
Psiphon tunnel carries **one** udpgw port forward, so opening the second tore the
first down, whose next datagram re-dialled it, which tore down the second — a
ping-pong at up to 48 SSH channel opens a minute, each one paying both hops of a
chained session (~300–900 ms on this path). Consequences:

* for most of the session there was **no live udpgw stream at all**, so every
  non-DNS datagram was silently dropped (QUIC, WebRTC, VoIP, games) and every
  lookup fell back to the slow DNS-over-HTTPS path;
* the churn is itself heavy load on the tunnel it is trying to use.

### 3. ~2.6 MB of local queue in front of the WireGuard writer — `lib.rs`, `netstack.rs`

The two packet handoff queues were sized from `sysprofile::channel_capacity()`,
an *application* queue depth: 1024 on a high-tier phone. 1024 packets at MTU
1280 is ~1.3 MB per direction, plus `MAX_TX_RETAINED = 2048` retained in the
device queue. Several seconds of standing buffer on a mobile uplink. The packets
were not lost, they were queued behind each other — textbook bufferbloat, and a
large part of the 2000 ms even once (1) stopped starving the path.

## The fix

| # | Change |
|---|---|
| 1 | **Fair scheduling.** Every queue is drained on every pass with its own budget (`MAX_INGEST_PER_TICK`, `MAX_APP_INGEST_PER_TICK`, `MAX_CMD_PER_TICK`), the visit order alternates each pass, and nothing is awaited while any queue still has work. `select!` is now only reached when all three are empty, i.e. when it is a pure wake-up with nothing left to be unfair about. `biased` is gone. |
| 2 | **Starvation telemetry.** If the app→network direction makes no progress for 2 s while packets keep arriving, the log says so in one line (rate-limited to one per 10 s) instead of leaving the next reader to infer it from a byte ratio. |
| 3 | **One udpgw stream.** DNS is prioritised where prioritisation actually works: a priority queue in front of the single non-blocking writer thread, with bulk frames dropped oldest-first — which is also the loss signal QUIC needs. |
| 4 | **Dial rate limit.** udpgw dials are gated by a 400 ms floor with exponential backoff to 5 s, so a failing forward can no longer become a channel-open flood; dials are counted and the count is logged, so churn cannot come back unnoticed. |
| 5 | **Queue depth as a device queue, not an app queue.** Packet handoff queues capped at 256 packets, `MAX_TX_RETAINED` 2048 → 256. Throughput is set by smoltcp's socket buffers, not by these rings, so this costs no bandwidth and returns the queueing delay. |

## What to expect now

Dubbing should behave the way it does on a single-hop VPN: upload and download
scheduled together, RTT staying in the low hundreds of ms instead of climbing
without bound, no "carries nothing" windows, and no self-healing 6-second holes.
The udpgw line should appear a handful of times per session, not 219.

If the ping still climbs, the log will now name the reason: look for
`[netstack] app->network idle for …` (a starved upload — should never appear
again) and for `udpgw stream up (#N)` with a high `N` (churn returning).

## Regression tests

* `netstack::tests::a_saturated_download_cannot_starve_the_upload_path` — the
  1.2.8-r2 test: a permanently full inbound queue must not stop app data being
  placed in the same pass.
* `netstack::tests::a_wedged_flow_cannot_block_another_flows_writes` (1.2.8) —
  still passing.
* `netstack::tests::netstack_keeps_draining_inbound_when_outbound_is_never_read`
  (existing) — the reader must never park forever.
