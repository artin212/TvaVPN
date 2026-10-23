# Root-cause analysis — `Aether` / `Aether + Psiphon`: apps report "no internet", browsers open nothing, CapCut effects never load

Version: **1.2.7** (unchanged; this is a fix pass inside 1.2.7, marked `1.2.7-r3` in code comments)
Evidence: the field log `loge.txt` (18:04:57 → 18:07:08, chained session, exit `82.165.110.196` / DE) plus two user reports.

## The reports

1. In `Aether → Psiphon`, the AI apps on the phone (Gemini, ChatGPT and others) say **"you are not
   connected to the internet"** or show a **sanctions/country block**. In the *same* session, with
   the phone tethered to a laptop over USB, `gemini.google.com` and `aistudio.google.com` open
   instantly in the laptop's browser.
2. In `Aether → Psiphon`, **only Telegram and Instagram work**. Browsers are dead, AI apps say
   there is no internet. Identical on ADSL, MobinNet and Irancell.
3. CapCut must open and load its effects in **both** modes; with plain Aether, TikTok does not open.

## The decisive clue is in report 1

The tethered laptop path is **TCP-only and IPv4-only**: PdaNet hands the laptop a proxy, so
nothing the laptop does can produce a QUIC datagram or an IPv6 flow. On the phone, the same session
serves the same sites over the same tunnel and fails. So the failure is not the tunnel, the exit or
the ISP - it is **exactly the two things the tether path strips**. The log agrees, and prices them:

```
18:07:07.977  PsiphonSocksFront session drops: udp/443 (QUIC) dropped=48,
              congested udpgw frames dropped=0, IPv6 flows refused locally=65
```

Forty-eight silently discarded QUIC datagrams and sixty-five refused IPv6 flows in a two-minute
session whose entire purpose was opening Gemini.

## 1. UDP/443 was black-holed, and a black hole is not a fallback signal

1.2.7-r2 dropped every UDP/443 datagram (`SUPPRESS_QUIC`) to protect the tunnel from a video
stream. Browsers tolerate that: Chromium races HTTP/3 against TCP and takes whichever answers.
**The apps in these reports do not.** Gemini, ChatGPT, CapCut and TikTok are Cronet / `TTNet`
clients that pin HTTP/3 for their own origins, and SOCKS5 `UDP ASSOCIATE` has no way to return an
ICMP port-unreachable - so a dropped datagram is indistinguishable from a dead link, and the app
reports precisely that: *no internet*. CapCut's effect and template CDN is QUIC-first, which is why
the app opens and its content never arrives.

**Fix** (`PsiphonSocksFront.CARRY_QUIC`): UDP/443 is carried again. r2's two real mechanisms are
fixed where they live instead of being papered over:

* **Head-of-line blocking** came from DNS and bulk UDP sharing ONE udpgw port forward. r2 answered
  it with a priority *queue*, which cannot work: the blocking happens in the kernel send buffer and
  in the tunnel's own flow control, **below** anything this class can reorder. DNS now has **its own
  udpgw stream** (`UdpgwLane`), so a saturated video flow cannot delay a name lookup by one byte.
* **Congestion control fighting itself** came from a reliable tunnel hiding loss from QUIC. The bulk
  lane's queue is bounded and **drops** instead of buffering, which is exactly the loss signal QUIC's
  congestion controller needs - it backs off instead of inflating the RTT without bound.
* And if it degrades anyway, a breaker (`noteBulkDrop`) suppresses UDP/443 for 60 s and re-enables it
  automatically. r2's protection, without permanently breaking the apps.

## 2. The device was told it had IPv6, by an exit that has none

`establishTun` put an IPv6 **address** and a `::/0` route on the TUN. The route is correct - it is
what stops IPv6 leaking past the tunnel. The address is the damage: it is the one thing that tells
Android's resolver "this network has IPv6", and from that moment `getaddrinfo` hands AAAA records to
every app and Happy Eyeballs prefers them. Psiphon exits are IPv4-only in practice, so each of those
is a connection that must fail first - 65 of them here - and it also fails Android's own network
validation, which is what makes an app say "no internet" before it has tried anything.

**Fixes**

* `AetherVpnService.buildTun`: in a chained session the TUN carries **no IPv6 address** while still
  routing `::/0`. Android's `netd` decides whether to return AAAA by testing for a usable IPv6
  *source* address, so the platform now filters AAAA per-network for every app by itself, and `::/0`
  still swallows anything an app produces on its own. If a ROM refuses such an interface, the old
  shape is re-established automatically and the front's suppression carries the fix alone.
* `PsiphonSocksFront`: AAAA queries are answered locally with an empty `NOERROR` (NODATA) once the
  exit has **proven** it cannot dial IPv6, and the proof is now obtained **up front**, once, on a
  background thread (`probeIpv6Capability`) instead of being paid for by the first two app flows of
  every session and every rotation.
* `::/0` is routed unconditionally in a chained session, even when the profile's IPv6 leak
  protection is off. An uncaptured IPv6 flow leaves with the phone's real address, and the site
  answers with a **country block** - which is the sanctions error in report 1, with the app still
  showing Connected.

## 3. Report 2's signature: name resolution, not the ISP

"Only Telegram and Instagram work" is a DNS failure with a fingerprint. Telegram dials hard-coded
datacentre IPs and needs no resolver at all; a warm Instagram has its addresses cached; a browser
needs twenty to thirty fresh names to render one page, and an AI app needs its API host. Two defects
made resolution collapse on any server that refuses the udpgw intercept:

* **The udpgw dial ran on the datagram pump thread**, holding a lock every other association needed.
  The pump is the only reader of the forwarder's UDP socket, so for the length of that dial **UDP
  stopped for the whole device, DNS included** - the same class of bug r2 fixed for the udpgw
  *writer* and missed for the dial. **Fix:** dialling moved onto a dedicated pool (`dialPool`); a
  pump that finds no live lane gets `null` immediately, and both lanes are pre-warmed before the TUN
  is even up.
* **The DNS-over-HTTPS fallback sent `Connection: close`**, so every single name cost a fresh port
  forward *and* a fresh TLS handshake through a chained tunnel - seconds per lookup, sixteen at a
  time. **Fix:** connections are pooled and reused (`DOH_IDLE_MS`, `DOH_POOL_MAX`), with Cloudflare
  and Google as resolvers, so a page load costs one round trip per name on a warm connection.
* Also hardened: `openPsiphonStream` read the SOCKS handshake with **no timeout at all**, so a
  listener that accepted and never answered parked the calling thread for the life of the session -
  a lane that never came up, or a DNS worker gone for good. Bounded now
  (`PSIPHON_HANDSHAKE_TIMEOUT_MS`).
* Also surfaced: a refused **TCP/853** is now named in the log. That is where Android's *Private
  DNS* goes, and in **strict** mode (a hostname typed into the setting) Android has no fallback - so
  the device resolves nothing while the tunnel is perfectly healthy. No app can change that setting
  for the user; the least this can do is stop it looking like the tunnel's fault.

## 4. `hev.yaml`

`udp-read-write-timeout: 60000 → 120000`. r2 lowered it because UDP/443 was dead weight. Now that
QUIC is carried, a live QUIC connection legitimately idles - a paused video, a backgrounded app, an
idle HTTP/3 pool - and reaping the association forces a new source port, which the peer answers with
a fresh handshake. That is the "effects never load / feed stops mid-scroll" symptom.

## 5. What this does NOT fix: TikTok and CapCut on plain Aether

Report 3's second half is not a tunnel defect and it is dishonest to present a tuning change as a
cure. With plain Aether the exit IP belongs to **Cloudflare WARP**, and ByteDance rejects those
ranges outright - the request reaches TikTok and TikTok declines it. No MTU, timeout or QUIC setting
changes an exit IP's reputation, and routing those domains *direct* is worse (they are filtered
locally). The mode that can serve them is `Aether → Psiphon`, whose exit is an ordinary hosting IP,
and that mode is what this pass fixes: with QUIC carried, AAAA suppressed and DNS on its own lane,
CapCut opens and its effects load, and so do Gemini and ChatGPT.

## Files touched

* `app/src/main/java/studio/cluvex/aether/transport/PsiphonSocksFront.kt`
* `app/src/main/java/studio/cluvex/aether/vpn/AetherVpnService.kt`
* `docs/PSIPHON_APP_CONNECTIVITY.md` (this file), `CHANGELOG.md`

Version deliberately unchanged: **1.2.7**.
