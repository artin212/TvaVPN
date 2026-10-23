# The mid-video stall: root cause and fix (1.2.8)

## The report

> On `Aether -> Psiphon`, GOOL, exit automatic: connect, and the first minute is
> fine. Open YouTube and play a video: the ping jumps to about 2000 ms and then
> nothing moves at all - the video stops, no other site opens, the app still says
> Connected. Disconnect and reconnect and it works again, until the next video.
> The same ping spike happens on plain `Aether` too.

## What the log proves, and what it rules out

The attached log covers a healthy connect (both WARP hops validated end to end,
stage 1 up on 1819, Psiphon up behind the front on 1825, `dns+http OK`, exit
`172.232.128.113/SE`) and then a clean user-initiated teardown 15 s later. It
contains **no** failure. That is itself the most useful fact in it: at the moment
the session dies, nothing anywhere in the stack logs a thing. Whatever breaks
breaks in a code path that swallows its own error.

Two more facts narrow it down hard:

1. **It happens on plain `Aether` too.** Everything Psiphon adds is therefore
   innocent. What both modes share is the engine's data plane: the WireGuard
   session, the netstack, and the SOCKS5 listener in front of them.
2. **It stays "connected" and needs a manual reconnect.** So the process, the
   listener and the TUN all survive. Something *inside* them stops carrying
   traffic and nothing notices.

## Root cause

Five defects on that shared path, in the order they fire.

### 1. The socket readers go deaf under load (`wireguard.rs`, `lib.rs`)

The WireGuard reader handed each decapsulated packet to the netstack with an
unbounded `inbound_tx.send(..).await`. A video player fills that queue in
milliseconds. While the reader is parked it is not reading the UDP socket - and
it is the only task that ever does.

### 2. ...and the socket's receive buffer is far too small (`upstream.rs`)

`sysprofile` has sized these buffers since 1.2.5 and logs the number at startup
(`udp socket buffer=7168KB`), but only the QUIC/MASQUE path ever applied it.
Every WireGuard socket ran on the OS default - a few hundred KB, i.e. a few
milliseconds of a 4K stream. Combined with (1): the kernel starts discarding
datagrams, and it discards them blind. Among them are WireGuard's handshake
replies.

### 3. An expired session was ignored instead of replaced (`wireguard.rs`)

boringtun retries a rekey for a while, then gives up and reports it once, from
`update_timers`. The timer task matched only the `WriteToNetwork` arm:

```rust
if let TunnResult::WriteToNetwork(pkt) = tunn.update_timers(&mut tmp) { .. }
```

The report was discarded. After it, `encapsulate` refuses every packet - and
that arm was a `log::trace!`. So: a live tunnel object, a live netstack, a live
SOCKS5 listener, a live TUN, and a dead crypto session in the middle. Nothing is
sent, nothing arrives, nothing is logged, and no amount of waiting fixes it.
**This is the stall.** The 10 s staleness check was the only thing that could
have caught it, and it was tuned so tightly that a congestion spike tripped it
first - which in gool mode tears down *both* hops and the listener, blacklists
the edge and pays for a rescan.

### 4. One stalled flow froze all of them (`netstack.rs`)

```rust
maybe = data_in_rx.recv(), if deferred.is_empty() => { .. }
```

`data_in` is the only route from the app into the network, and it was gated on a
single global deferred queue. One flow that could not accept another byte - a
video segment whose peer stopped reading is the everyday example - stopped the
writes of every other flow, DNS included. smoltcp sockets had no keep-alive and
no timeout, so such a flow never died on its own either.

### 5. The watchdog could not see any of it (`AetherVpnService.kt`)

```rust
java.net.Socket(proxy).use { it.connect(target, PROBE_TIMEOUT_MS) }
```

A SOCKS5 connect is answered by the engine's *accept* path, which is a different
code path from the one that carries payload - and in the netstack it travels on
a different channel (`cmd_tx`) from app data (`data_in`). So a wedged tunnel
passed every check, forever. A chained session was worse: it only asked "is the
Psiphon library still running?", and it always was. That is precisely why the
user had to reach for the switch.

## The fix

| # | Change |
|---|---|
| 1 | Packet handoff is bounded at 20 ms in both the WireGuard reader and the gool relay. Late is fine; going deaf is not. Congestion drops are the loss signal TCP and QUIC are built to read. |
| 2 | `tune_udp_buffers` is applied to every socket from `bind_via_upstream`, and to the gool loopback relay. |
| 3 | The session's keys travel with it, so an expiry (or a run of refused packets, or a quiet data plane) triggers an **in-place re-handshake on the same socket**, rate-limited to one per 3 s. The netstack, the SOCKS5 listener and Psiphon's upstream never see a gap. Only a session that cannot even start a handshake is escalated to the supervisor. |
| 4 | The backlog is ordered **per flow**: a stuck flow queues behind itself, nobody else waits. Flows that accept nothing for 30 s are reset; TCP keep-alive (15 s) and a dead-peer timeout (90 s) are set on every socket. `flush_tx` holds a burst and retries instead of shredding it. |
| 5 | The probe completes a real DNS round trip and requires a valid answer. Chained sessions get the same probe against the pipeline's own listener every 15 s and rebuild after two dead readings. Stale timeout 10 s -> 20 s with two confirmations, with the in-place rekey attempted at half that. |

## What the user should now see

The congestion spike still happens - it is the network, not the app. What
changes is that it stays a spike. A session that used to die silently and stay
dead now either never notices (per-flow backlog, bigger buffers, held bursts) or
re-handshakes itself within a few seconds; and in the worst case the watchdog
proves the path is dead within ~45 s and rebuilds it without anyone touching the
switch.

## Regression tests

- `netstack::tests::a_wedged_flow_cannot_block_another_flows_writes` - the
  head-of-line fix, asserted both ways: the healthy flow's bytes go through, and
  the stuck flow's own bytes are not reordered.
- `netstack::tests::flush_tx_holds_a_burst_back_instead_of_shredding_it`
- `netstack::tests::netstack_keeps_draining_inbound_when_outbound_is_never_read`
  (existing, still passing: the reader must never park forever)
