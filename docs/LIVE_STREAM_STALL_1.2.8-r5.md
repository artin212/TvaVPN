# 1.2.8-r5 — Why r4 changed nothing, and the two real fixes

## Part 1: the r4 build was never tested

`loge.txt` (2026-09-02, 10:35:47 → 10:42:04, `Aether → Psiphon`, IR-MCI Wi-Fi) was
produced by the **r3** build. Five independent strings prove it. Every one is a
mechanical comparison against the source that was sent back with this round, and
each one on its own is sufficient.

| # | r4 change (present in the sent source) | What the log had to contain | What `loge.txt` actually contains |
|---|---|---|---|
| 1 | `sysprofile::log_summary` rewritten | `netstack tcp tx/rx=512KB/192KB (rx = advertised window), netstack udp=128KB` | `netstack buffers=512KB/128KB` — the r3 wording, and the r4 string exists nowhere in the tree |
| 2 | `[netstack]` telemetry every 15 s | ~24 lines in a 6-minute session | **zero.** The engine's last line is at second 51 (`10:35:51.416`) and it never speaks again |
| 3 | `PingMonitor` warm session | `Warm latency session up via …`, failures as `dial failed` / `no answer on an established session` | `Latency probe failed (viaTunnel=true): 9.9.9.9:443 Connect timed out` — r4 cannot emit that wording |
| 4 | Psiphon noise filter | one `(+N routine server-list notices suppressed)` | **260** raw `updated server` notices, no suppression line |
| 5 | Scan RTT quality gate | a gated selection | `396.851077ms` cache rejected → `475.011923ms` accepted, 2 ms apart: the exact contradiction r4 was written to remove |

Screenshot `a1` agrees: `Latency 4962 ms` is the r3 badge measuring an SSH channel
dial, which is the bug r4's warm session removed.

### The meta-cause: there was no build identity

`versionName "1.2.8"`, `versionCode 12` in r2, r3, r4 and r5 alike. The engine
banner printed only the **upstream core** version, `Aether v1.8.0`, identical in
all of them. Nothing in the app, the exported log, or the releases page could tell
two revisions apart. Five rounds of analysis were spent on binaries nobody could
identify. That is not a mistake care can prevent — it is a missing field.

r5 adds it, end to end:

* `PATCHLEVEL` at the repo root (`1.2.8-r5`) is the single source of truth.
* `native/aether/aether/build.rs` stamps `AETHER-BUILD-STAMP:1.2.8-r5` into
  `libaether.so` and the engine prints it as its **second line**.
* `scripts/build-natives.sh` greps the *stripped* `.so` for it and fails the build
  if it is absent. It also deletes any previous `libaether.so` from the
  git-ignored `jniLibs` first, so a local build can never reuse a stale engine.
* CI greps it again *inside the packaged APK*, per ABI, before publishing.
* `BuildProvenance` compares APK vs engine at runtime and logs
  `ENGINE/APK PATCH LEVEL MISMATCH` at ERROR if they differ, or a warning if the
  engine never identifies itself (which means it predates r5).
* The About card shows it, in red on mismatch.

**Line 2 of every future log now answers "am I testing the right build?".**

### The latent bomb found while looking: CI was scheduled to delete the fix

`scripts/sync-core.sh` does `rm -rf "$CORE_DIR/aether"` and restores only a
**hand-written** list of three files (`prober.rs`, `wg_prober.rs`, `lib.rs`). Every
r4 change lived outside that list:

* `aether/Cargo.toml` — the `socket-tcp-cubic` feature, i.e. r4's root-cause fix
* `aether/src/netstack.rs` — `set_congestion_control`, telemetry, every queue bound
* `aether/src/sysprofile.rs` — the split tx/rx buffer sizing
* `aether/build.rs` — the whole provenance stamp

So the first time upstream cut a release, our own CI would have deleted the fix,
with a green build and a changelog line saying the upgrade went cleanly. r5:

* the file list is **discovered** from `AETHER-APP-PATCH` markers, never written
  by hand, and every r4/r5 edit is now wrapped in them;
* a marker count that changes across the merge is treated as an incomplete
  rebase;
* a patch that cannot be rebased **fails the build** instead of warning. An
  automatic convenience upgrade may not silently revert an engine fix.

## Part 2: the fix r4 got right, and the reason it would still have failed

r4's diagnosis was correct: `default-features = false` had switched off
`socket-tcp-cubic`, so smoltcp resolved `NoControl` and the TCP sender had no
congestion window at all. Necessary. Not sufficient — because of nine lines in
`netstack.rs`:

```rust
fn transmit(&mut self, _t: Instant) -> Option<Self::TxToken<'_>> {
    Some(StackTxToken(&mut self.tx))     // always. tx is an unbounded VecDeque.
}
```

To smoltcp that advertises a link with **infinite capacity and zero latency**.
Every segment CUBIC hands the device is accepted instantly and accounted as
*sent*. `flush_tx` then discovered the WireGuard writer was full and threw packets
away — *after* smoltcp had recorded them as transmitted. And it dropped **exactly
one packet per pass before `break`ing**, so under sustained pressure the queue grew
past `MAX_TX_RETAINED` without bound while the counter crawled one per 2 ms.

So the loss our own bottleneck produced was invisible to the controller whose only
job is to react to loss. CUBIC would have seen a link that never dropped and never
delayed anything, opened its window to the maximum, and kept it there while the
packets it "sent" were shredded one queue later. The only feedback left is the far
end's retransmission timer, seconds away. **r4 removed the missing controller;
this removed the controller's ability to work.** Same self-destruct loop, one layer
down — and it is why a sustained upload (live dubbing, ~44 KB/s of PCM) dies while
downloads paced by a *remote* controller look fine.

`a1` shows the shape exactly: **1.1 KB/s up against 49.7 KB/s down**, 271 KB sent
against 891 KB received, during a session whose whole purpose is a continuous
upload.

### r5 changes

1. **Device backpressure** (`transmit()` refuses at `MAX_DEVICE_TX = 64` packets,
   ~82 KB, ~150 ms of a 4 Mbit/s uplink). smoltcp keeps the segment in the socket
   buffer, CUBIC's in-flight accounting stays honest, and backpressure travels up
   to the sending app instead of becoming a retransmit storm. `receive()` still
   always hands out a token: throttle our sender, never acknowledgements.
2. **`flush_tx` holds instead of shredding.** Bursts are retried in 2 ms. Shedding
   only happens at a hard ceiling, sheds the whole excess at once, head-drops the
   stalest packet, and is counted separately as the "controller is being lied to"
   alarm.
3. **`MAX_BACKLOG_BYTES` 8 MB → 512 KB.** 8 MB of PCM at 44 KB/s is over three
   minutes of audio queued. That number was sized to "cannot exhaust RAM"; it is a
   queue in front of a mobile uplink.
4. **A hard RTT floor on endpoint selection.** r4's doc comment claimed it was
   "impossible for this build to reject an endpoint as too slow and then choose a
   slower one." Not true: the target only governs whether turbo commits *early*;
   when nothing beats it, the slow first answer is still returned — 475 ms, against
   a 396 ms cache that had just been discarded. Discarding the cache is now a *bet*
   that registers what the scan must beat, and a lost bet keeps the cache. Rejecting
   a cached endpoint can only ever improve the endpoint now.
5. **First telemetry line at 2 s**, then every 15 s, and it carries
   `device queue peak N/64 pkts, backpressure N` alongside the tail-drop counters.
   r4 waited a full 15 s, so a short "start dubbing, watch the ping, stop"
   reproduction could finish having never reported once.

## How to read the next log

1. **Line 2.** `AETHER-BUILD-STAMP:1.2.8-r5`. If it is absent, or `W/build` says
   mismatch, stop: the engine is stale and nothing else in the log means anything.
2. **The sysprofile line** must say `netstack tcp tx/rx=…KB/…KB (rx = advertised
   window)`. The old `netstack buffers=` wording means pre-r4.
3. **During dubbing**, read the `[netstack]` line:
   * `backpressure` climbing and `outbound tail-drops 0` → **fixed.** That is the
     uplink being throttled correctly.
   * `outbound tail-drops` climbing, or the `shed at the hard ceiling` warning →
     send the log. The number says which queue and how deep.

Compiling was not possible here (the sandbox has no internet and no Android SDK),
so build through CI. The build now fails loudly rather than shipping an
unidentifiable engine, which is the point.
