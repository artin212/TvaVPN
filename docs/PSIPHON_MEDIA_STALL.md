# Root-cause analysis — `Aether + Psiphon`: video playback makes the ping exceed 1000 ms and the tunnel stall

Version: **1.2.7** (unchanged; this is a fix pass inside 1.2.7, marked `1.2.7-r2` in code comments)
Evidence: the field log `loge.txt`, 09:35:18 → 09:39:26, one session, three server rotations.

## The report

> On some servers, in Aether + Psiphon mode, when I open YouTube (or any page with video)
> and press play, the ping goes above 1000 ms and the speed becomes infinitely slow and stops.

It is not normal, and it is not the network. Four defects compound into it, and the log names each one.

## 1. The device prefers IPv6; the Psiphon exit has none

`AetherVpnService.establishTun` puts an IPv6 address (`fc00::10:10:14:1/126`) **and** a `::/0`
route on the TUN (IPv6 leak protection, on by default). Apps therefore see working IPv6 and
prefer `AAAA` records. Psiphon's exit servers are IPv4-only in practice, so each of those flows
comes back:

```
ssh: rejected: administratively prohibited
```

Ordinary browsing barely notices; one video does not. A player fans out dozens of segment
connections at once, and the log shows the result the instant playback starts:

```
09:35:25.567  ActiveTunnel {"diagnosticID":"vpDb1v+6","protocol":"OSSH"}   <- healthy, 86 s of quiet
09:36:52.520  port forward failures for vpDb1v+6: 1
   ...                                                  26 of them in 4.2 seconds
09:36:56.708  port forward failures for vpDb1v+6: 26
```

**Fix** (`PsiphonSocksFront`): the front latches "this exit has no IPv6" after
`IPV6_PROBE_BUDGET = 2` refused dials and then answers IPv6 flows itself with SOCKS5
`host unreachable`, in microseconds. lwIP turns that into an immediate reset, the app's
Happy-Eyeballs timer fires at once and retries over IPv4. The verdict is re-probed once after a
server rotation, because it describes the **exit**, not the tunnel. IPv6 UDP is dropped on the
same verdict. IPv6 refusals are never reported as censorship.

## 2. The watchdog answered load by tearing the tunnel down

`PsiphonHealth` rotated on "25 tunnel-reported port-forward failures in 45 s". That counter is a
**raw, monotonic count of every declined port forward for any reason**, so it measures how busy
the session is, not whether the server filters. 26 refusals is one second of video:

```
09:36:56.719  PsiphonHealth rotating off server vpDb1v+6 (25 refused port forwards) - reconnect 1/4
09:36:58.361  Tunnels: {"count":0}
09:36:58.378  psiphon...Dial: no active tunnels          <- the >1000 ms ping the user sees
09:37:04.079  ActiveTunnel {"diagnosticID":"RAanom/G"}   <- ~6 s hole, mid-video
```

It happened three times in two minutes, the second one as a full `restartPsiphon()`
(`Exiting: {}` at 09:38:18, tunnel back at 09:38:23). The watchdog was the cause of the symptom
it was built to detect.

**Fix** (`PsiphonHealth`): the tunnel counter becomes a **corroborating** signal. The trigger
moves to 60, and it may only rotate when the front has also recorded refusals for
`FAILURE_CORROBORATION = 3` **distinct** destinations in the same window — the only signal that
distinguishes "refuses many different places" from "is simply busy". Uncorroborated bursts are
logged once every 20 as load, and nothing is torn down.

## 3. After each rotation, DNS burned one refused port forward per lookup

The replacement servers refused the udpgw intercept, so DNS fell back to **TCP/53** — a port most
Psiphon servers do not allow out (`AllowTCPPorts`). Every single lookup became one refused port
forward, which then fed straight back into the watchdog as fake evidence of censorship:

```
09:36:58.368  PsiphonSocksFront this server refused the udpgw port forward (SOCKS reply 1)
09:37:05.806 → 09:38:17.291   110 port forward failures in 73 s
09:38:18.734  (next server) refused the udpgw port forward too
```

**Fix** (`PsiphonSocksFront`): a refused TCP/53 dial latches `dnsPort53Refused`, and every later
lookup goes over **DNS-over-HTTPS on 443** (RFC 8484) through the same tunnel — a port no Psiphon
server blocks. The resolver host is sent as a SOCKS5 hostname so Psiphon resolves it inside the
tunnel; TLS is verified against the system trust store **and** hostname-checked explicitly.
Refusals on port 53 and on the udpgw address are no longer reported as censorship.

## 4. QUIC video and every DNS query shared one TCP stream

This is the direct mechanism of "ping over 1000, speed infinitely slow, then it stops".

udpgw is **one** TCP port forward for the whole device, multiplexed by connection id. Fine for
DNS-sized datagrams. Catastrophic for a video stream:

* **Head-of-line blocking.** A multi-megabit QUIC flow and every DNS query the device makes share
  one byte stream. While a video segment sits in the write queue, name resolution is stuck behind
  it.
* **Congestion control fighting itself.** QUIC's loss-based congestion control sees no loss,
  because the TCP tunnel hides it. QUIC keeps opening its window, the tunnel keeps buffering, RTT
  inflates without bound — the classic TCP-over-TCP meltdown.
* **Worse still, the writer blocked the reader.** Frames were written straight from the datagram
  pump under one lock with a blocking `write` + `flush`. A congested tunnel blocked the pump, and
  the pump is the only reader of the forwarder's UDP socket — so **UDP stopped for the whole
  device, DNS included**, for as long as the congestion lasted.

**Fixes** (`PsiphonSocksFront`):

1. **UDP/443 is not carried** (`SUPPRESS_QUIC`). Dropped from the first datagram so browsers and
   the YouTube player fall straight back to HTTP/2 over TLS, where every flow gets its own Psiphon
   channel with its own flow control. Consistency is the point: intermittently working QUIC is far
   worse than QUIC that never works, because Chromium caches "HTTP/3 works for this origin" and
   then spends seconds per request on a handshake a rotated server has silently black-holed. DNS
   and all other UDP (VoIP, games, NTP) still ride udpgw untouched.
2. **A bounded, prioritised, non-blocking writer.** The pump only enqueues; one dedicated thread
   does the blocking I/O; DNS has its own lane a saturated bulk flow cannot get in front of; bulk
   frames are dropped oldest-first at `UDPGW_BULK_QUEUE = 256`. Writes are batched, one flush per
   batch. The pump can no longer be blocked by anything.
3. **Connection-id hygiene.** `nextConid and 0xFFFF` wrapped after 65535 flows and could hand out
   an id the server still had a socket bound to, delivering one flow's replies to another. Ids are
   now checked against live flows and never 0, and the `conids` map is bounded together with
   `flows` instead of growing for the life of the session.

## 5. Forwarder tuning (`hev.yaml`)

* `connect-timeout: 5000 → 12000` — a chained session pays stage 1's latency *and* Psiphon's own
  channel dial per flow; under a player's fan-out those dials routinely need longer, and hev
  abandoning them mid-load is itself a stall.
* `udp-read-write-timeout: 120000 → 60000` — a media session leaves hundreds of dead associations
  behind; pinning each for two minutes wastes slots live flows need.
* `limit-nofile: 65535` added — a video player opens flows in bursts of dozens.

## 6. The latency badge was measuring the wrong hop

`PingMonitor` probed `127.0.0.1:1819` unconditionally — in a chained session that is **stage 1's**
listener, so the number described the first hop and not the path the traffic actually takes. It now
follows the port the finished pipeline exposes (`PingMonitor.setTunnelPort`, published by
`AetherVpnService`), and resets on teardown.

## Expected behaviour after the fix

YouTube plays over HTTP/2 instead of HTTP/3, DNS stays fast under load, IPv6 flows fail instantly
instead of after a round trip, and a busy session no longer convicts a healthy server. Rotations
now only happen when a server genuinely refuses many different destinations. The version stays
**1.2.7**.
