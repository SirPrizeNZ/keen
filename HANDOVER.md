# Keen Zero — Handover (dlhd.st ad-blocking + playback)

**Device:** Xiaomi Mi TV, `adb connect 192.168.68.57:5555` (IP drifts; ask user if it fails).
**Package:** `com.keenzero.app.v7a`. **Current shipped build: v0.1.126 (versionCode 146).**

## 🔑 Cloudflare / bot-challenge sites (1337x.to, ext.to) — SOLVED v0.1.121→126
**Root cause, proven by bisect (not inference): our own document-start JS.** Sequence of
controlled runs on-device:
| profile | result |
|---|---|
| stock WebView UA (`keen_stock_ua`) | still looped ~26s |
| Hls hook gated to player pages | still looped |
| **all injection off** (`keen_no_inject`) | **1337x loaded first time** |
| **guard off only** (`keen_no_guard`) | **1337x loaded, 0 reloads/60s** |

Two independent defects, both fixed:
1. **We were injecting INTO the challenge provider's own frame.** `addDocumentStartJavaScript`
   uses `setOf("*")` (required for the dlhd nested-embed chain), so the bundle also landed in
   `challenges.cloudflare.com` — observed as `KZ_FRAME_BOOT:sub challenges.cloudflare.com`.
   Cloudflare's verification ran in a document carrying our observers, timers and listeners.
   Fixed by a prelude in the WebViewHost bundle setting `__keenProviderFrame`; all four
   scripts (guard, player agent, both scroll scripts) bail on line 1 there. Logs `KZ_FRAME_SKIP`.
2. **`HostileOverlayGuard.arm()` mutated the page before it could know it was a challenge.**
   Gating the *removals* was not enough — arming still started a MutationObserver, appended a
   `<style>` element and ran a first sweep while the DOM was still empty. Now `arm()` returns
   early while `keenChallengeActive()` is true and retries every 1s; the stylesheet moved
   inside `arm()`; first arm delayed 1200ms. Nothing is permanently disabled.

Also: challenge infrastructure is allowlisted in `RequestBlocker.isChallengeInfrastructure()`
**before** the path/DGA heuristics (they are third-party to the protected site, and
`"fingerprint"` is one of our needles). Host-scoped deliberately — a blanket `/fp/` allow
would be a real hole, since ad networks fingerprint on that path too. Verified 9/9 cases:
provider origins + first-party `/cdn-cgi/challenge-platform/` allow; `fake-cloudflare.com`,
`challenges.cloudflare.com.evil.net` and `*/fp/check.js` still block.

**D-pad on the checkbox:** the hit-test resolves the Turnstile checkbox to a bare `DIV`
(shadow root + cross-origin iframe, so `elementsFromPoint` stops at the host) and the old path
fired a synthetic `MouseEvent`, which crosses neither boundary. While a challenge is active,
ACTIVATE_JS now always returns `method:'challengeTouch'` → real trusted `MotionEvent`.

**Do NOT "humanise" the synthetic touch** (movement jitter/hold timing to look less automated).
It was tried, correctly blocked, and reverted: it is anti-detection work, and the evidence says
the interaction is *received* — any remaining rejection is client scoring (WebView + this TV's
GPU failing Turnstile's WebGL fingerprint: `EXT_color_buffer_float` unavailable), not tap shape.

> **`adb shell screencap` DOES capture the video plane** (verified 2026-07-25 — it captured the
> live stream and the ad overlay). The old note claiming it shows black was wrong. Screenshot
> first; it is the fastest way to see what an ad actually looks like.
**Test URL:** `https://dlhd.st/watch.php?id=61` (beIN Sports MENA English 1) — confirmed working on user's MacBook.

## Build / deploy / cold-restart (one block)
```bash
cd "/Users/x/Documents/Projects/Keen Zero"
./gradlew :app:assembleArmeabiV7aRelease            # ~35s, R8
D=192.168.68.57:5555; adb connect $D
adb -s $D install -r app/build/outputs/apk/armeabiV7a/release/app-armeabiV7a-release.apk
adb -s $D shell am force-stop com.keenzero.app.v7a  # IMPORTANT: warm relaunch RESTORES the old
adb -s $D shell monkey -p com.keenzero.app.v7a -c android.intent.category.LAUNCHER 1
```
Bump `versionCode`/`versionName` in `app/build.gradle.kts` each build. Kotlin-only check: `./gradlew :app:compileUniversalDebugKotlin`.
Screenshot (UI/ads only — CANNOT capture the video plane; that shows black): `adb -s $D shell screencap -p /sdcard/s.png && adb -s $D pull /sdcard/s.png`.

## ✅ Done this session (v0.1.97 → v0.1.104)
- **Earlier bugs:** D-pad left ~40px cursor jump (`RemoteInputRouter` edge-parking yank removed); duplicate Continue-watching cards (`ContinuityStore` info-hash dedup).
- **Ad banner (first-party):** `dlhd.st/rs4k-adbanner.html` iframe removed by src-path in `HostileOverlayGuard.sweepHostile`.
- **Ad interstitial:** `z-index:300000` overlay removed via `looksHostileOverlay` (z≥100000 + not-containing-player rule).
- **Popup/click-hijack:** `WindowRequestBroker` unified onto the real blocklist (`BlockingRuntime.isHostBlocked`); removed `grant_play` blanket-open; cross-origin `window.open` on a gesture now **silently BLOCKs** (was a confusing "Open link?" confirm dialog). This is what fixed theatre-mode popup + click hijack.
- **Dating-cam overlay ("Jerkmate/Undress me/sent you a video"):** served by **per-load DGA random domains** (e.g. `vqjxckklhqfjv.website`, `rdipmrbwgvk.com`). Beat it with `RequestBlocker.looksRandomAdDomain()` — conservative random-host heuristic (0 vowels / ≤12% vowels+len≥9 / consonant-run≥5), third-party + non-media-path only, `SAFE_LABELS` allowlist. Verified it catches novel rotated hosts. Explicit current hosts also in `core-hosts.txt`.
- **Play button dead:** the play button is inside NESTED cross-origin iframes; activation was doing a synthetic JS click on the `<iframe>` (no-op). Fixed in `RemoteInputRouter` ACTIVATE_JS: pointer over an iframe → `method:'iframeTouch', needTouch:true` → native dispatches a **real MotionEvent touch** that propagates into the frame. (User confirms play + fullscreen now work.)

## ✅ Done this session (v0.1.104 → v0.1.105) — in-frame player agent
**Root cause of "always muted / never auto-fullscreen":** every media control path the app
had (`SAMPLE_JS`, `UNMUTE_AND_THEATRE_JS`, `OPTIONAL_FULLSCREEN_JS`) runs via
`webView.evaluateJavascript()`, which only ever executes in the **top frame**. On dlhd.st the
`<video>` is inside nested cross-origin iframes, so `document.querySelector('video')` returned
null every time and all of it silently no-opped. Same reason the pointer's play tap emitted no
PlayIntent: it resolves to `iframeTouch`, `play:false` — so no playback mode, no audio focus.

**New: `web/FramePlayerJs.kt`** — injected into EVERY frame via the document-start bundle
(`setOf("*")`), alongside `HostileOverlayGuard`. Everything is gated on a **real trusted tap in
that frame** (never autoplay). Four jobs:
1. **Unmute, persistently.** The embed's hls.js hits `autoplay error` and retries *muted*, so a
   one-shot unmute loses the race. Re-asserts `muted=false; volume=1` on every
   play/playing/volumechange plus a 400ms tick for 45s after the tap.
2. **Auto-fullscreen.** `requestFullscreen()` on the player container synchronously inside the
   tap handler (the only moment activation exists), retried on `playing`/`loadedmetadata` while
   activation may still be live (~4.5s). Child iframes get `allowfullscreen`/`allow="fullscreen"`
   stamped as they are inserted.
3. **CSS-fill fallback** when real fullscreen is refused (missing allowfullscreen on an ancestor,
   or activation already spent): the player frame fixed-fills itself and posts `{kind:'fill'}` up
   the chain; each parent expands the child iframe the message came from, to the top document.
   Needs no activation. Undone by `FramePlayerJs.EXIT_FILL_JS` from `exitAllHtmlFullscreen()` and
   `THEATRE_RESTORE_JS`.
4. **hls.js buffer fix for the stall (handover fix #2).** Wraps the `Hls` constructor
   (`Object.defineProperty(window,'Hls')`, statics carried via `Object.setPrototypeOf`) and also
   bumps live instances (Clappr's `player.core.getCurrentPlayback()._hls`) since hls.js re-reads
   `config` each buffering tick: `maxBufferLength 5→30`, `maxMaxBufferLength 120`,
   `backBufferLength 30`, and (constructor path only — changing it live forces a seek)
   `liveSyncDurationCount ≥5` so playback sits further behind the live edge. Costs ~15s of live
   latency, buys a cushion that survives the 5–11s segment gaps.

**Bridge back to native:** each frame relays media state to the top document
(`window.__keenFrameMedia`); `SAMPLE_JS` falls back to it when it has no local video, so playback
confirmation / audio focus / checkpointing work on embed sites again. The frame agent also logs
`KZ_FRAME_MEDIA_GESTURE_PLAY` once when media starts right after a tap → `WebViewHost.onConsole`
→ `adoptFramePlayback()` synthesises a PlayIntent (event `FRAME_PLAY_ADOPTED`), giving embeds the
full journey: immersive mode, KEEP_SCREEN_ON, audio focus, continuity checkpoints.

**Handover fix #1 (block `@swarmcloud`/`p2p-engine`) deliberately NOT taken.** If the script 404s,
`new P2PEngineHls(...)` throws a ReferenceError that can abort the rest of its script block —
including the hls attach. The buffer bump is the non-destructive fix; only try the block if the
bump proves insufficient, and verify attach survives.

## ✅ v0.1.108 → v0.1.110 (on-device verified)
**Play → fullscreen → unmuted CONFIRMED working** on dlhd, from logcat at 14:57:41:
`KZ_FRAME_TAP ... vids=1` → `KZ_FRAME_FS:gesture=requested` →
`KZ_FRAME_MEDIA:{"playing":true,"muted":false,"audible":true}`, screenshot showed true
fullscreen. Cross-frame gesture relay also verified: a tap in the TOP frame fanned out
(`KZ_FRAME_FS:remote:gesture=...`) to all 4 frames, the media frame took it, and when real
fullscreen was refused there the CSS fill engaged (`KZ_FRAME_FILL:self`). **The play control and
the `<video>` are in DIFFERENT frames** — that is why the first attempt logged `vids=0` and did
nothing; any future media feature must assume this.

**Ads: still UNPROVEN, do not assume fixed.** The "(2) Missed Messages / (00:51) Voice message"
fake-chat creative (screenshot 14:52) did NOT reappear in later runs and **no removal rule ever
fired**, so nothing here is confirmed to have killed it. These are geo/UA-targeted and rotate per
load — verify over several loads. Added, all unproven:
- `camAdLanguage()` needles for the fake-notification variant (missed messages / voice message /
  N new messages / is typing / incoming call). The first version of this filter would have MISSED
  the observed creative — it only had "oh hi there"/"sent you a video" wording.
- `foreignCreativeOverPlayer()` — copy-INDEPENDENT: inside a frame owning a `<video>`, a
  positioned layer whose img/iframe/a content comes from another registrable domain is not player
  UI (real controls are same-origin). This is the rule that should survive creative rotation.
- Blocked `.d11enq2rymy0yl.cloudfront.net` — caught by the new `blk=false 3p` diagnostic serving
  `/AEGdm/yframework7.min.js` + `/VqG/mframework7.min.js`: randomized path segments impersonating
  Framework7 (which really ships from cdnjs/jsdelivr and never randomizes). Best current suspect
  for the loader that injects the bubble.

**🔴 hls buffer fix NEVER ENGAGED** — zero `KZ_HLS_TUNED`/`KZ_HLS_BUMPED` across the whole
session, so the 5s buffer is still live and the stall risk is unchanged. The player is Clappr
(`DIV z=9999 "live media-control"` in the overlay dump) but neither `window.Hls` nor
`window.player.core.getCurrentPlayback()._hls` matched. v0.1.109 adds a bounded `Object.keys(window)`
scan for anything shaped like an hls instance (`.config && .levels && attachMedia`) or a Clappr
player. **If it still logs nothing, stop guessing at globals** — go for ExoPlayer (fix #3) instead.

### Also fixed v0.1.111 → v0.1.126 (user-reported)
- **`cbarackvuvdfv.online` (fake-chat "(2) Missed Messages" creative over the player) escaped
  `looksRandomAdDomain()`** — 13 letters, 23% vowels, longest consonant run only 4, so it slipped
  under all three rules (as do `mmirvipxdumpx`, `fafeyrfqyxwivk`, which the static list was
  silently carrying). Loosening the ratio globally would eat `romponalis`/`cloudflare` (both
  0.40), so the looser test is gated on `ABUSED_TLDS` (.online/.site/.website/…) + ≥11 letters +
  ≤36% vowels. Verified 6 known DGA hosts blocked, 12 real hosts allowed, 0 failures.
- **Back needed two presses after dlhd playback.** Back *did* work; `PlaybackOrchestrator`'s
  400ms poller then re-entered playback mode via `playing_reassert` ~2s later (Back does not
  pause the video), re-hiding the chrome. Latent for years — it only woke up once the
  frame-media bridge first made `playing` true for cross-origin embeds. Fixed with
  `reassertSuppressed`, set in `exitPlaybackMode()` *before* its early return, cleared by the
  next real Play.
- **URL bar moved to the BOTTOM of the screen.** `binding.chromeBar.bringToFront()` — the chrome
  bar is a child of the vertical LinearLayout `browseColumn`, and `bringToFront()` re-appends the
  view at the END of its parent's child list, i.e. lays it out last. It also desynced hit-testing
  (`chromeHeightPx()` still reported a top inset), so taps on the site's own top buttons opened
  the URL keyboard. **Only `pointerLayer`/`homeShell`/`errorShell`/`torrentLoadingOverlay` may use
  `bringToFront()` — those are root-FrameLayout children where it is z-order only.**
- **Back dead forever after playing a torrent.** `handleBack()` returns early on
  `nativeTorrentPlayerActive` / `torrentOverlayVisible`, both derived from view visibility. If a
  container survived teardown, every later Back re-entered that branch and did nothing. Both
  exits now force the views `GONE`.
- **Continue watching: same screenshot on most cards.** `persistTorrentFrame()` wrote every
  capture to ONE shared `continue/poster.img`, and the loader read that same file for any
  `frame:` poster — the per-title info-hash key was never used to pick a file. Now
  `continue/frame_<hash>.img` per title, with `pruneOrphanPosters()` reconciling the cache
  against the live 5 on every home hydrate (deletes evicted artwork + the legacy shared slot).
  No fallback to the old file on purpose: a placeholder beats another title's screenshot.

### Also fixed in v0.1.110 (user-reported)
- **Continue watching showed more than 5.** `loadRecents()` deduped but never capped — only the
  write paths did, so a list persisted by an older build rendered in full. Now sorts by
  `timestampMs` desc and caps on read.
- **Wrong artwork on some Continue cards.** `currentPagePosterUrl` was a free-floating "last
  artwork seen" with no tie to the page it came from, so a title whose own probe found nothing
  inherited the previous title's image. Now bound to `currentPagePosterForUrl` and only attached
  when `samePageKey()` matches; otherwise left blank (placeholder beats a wrong image).
- **Back felt broken during dlhd playback (needed several presses, no K logo / URL bar).** The
  exits were layered: press 1 peeled HTML fullscreen but STAYED in playback mode, leaving the
  video full-bleed with chrome hidden — so nothing appeared to happen. `EXIT_FULLSCREEN` now
  collapses both layers in one press and restores the chrome bar.

### 🐛 FIXED: Cloudflare "verify you are human" checkbox became unpressable
Regression from v0.1.104's extreme-z interstitial rule. **Turnstile mounts its interactive
challenge as a `position:fixed`, `z-index:2147483647` iframe covering the viewport** — exactly the
shape `looksHostileOverlay` strips as an ad interstitial. The existing `isCloudflareChallenge()`
guard only used `el.querySelector('iframe[src*=turnstile]')`, and **querySelector never matches
`el` itself**, so the widget iframe was unprotected: pressing the checkbox expanded the challenge
and the sweep deleted it within 120ms → dead checkbox. Fixed in `HostileOverlayGuard`: the guard
now matches the element's OWN src/title, covers Turnstile/reCAPTCHA/hCaptcha/Arkose/DataDome,
adds a structural `closest()` check (so the card survives before the widget loads), and the
ad-banner iframe src loop skips challenge srcs. Regexes verified against real widget URLs.

## 🔎 STALL INVESTIGATED (on-device, v0.1.105) — NOT our ad-blocking
Traced `KZ_NETDIAG`+`KZ_CONSOLE` through the stall. **Every stream host loads (`blk=false`):**
`index.m3u8`, `mono.m3u8` (live playlist, refreshing), and R2 `IMG_*.png` segments. **No stream host is
blocked by us.** Only ad hosts error (CORS) — correct. So the ad defence is exonerated.

**Actual cause = the web player, not us.** The embed configures hls.js with **`maxBufferLength: 5`**
(5-second buffer, see the innermost player page) + the **swarmcloud P2P engine**
(`cdn.jsdelivr.net/npm/@swarmcloud/hls/p2p-engine.min.js`, announce `ann.cdn-lab.shop`). Console shows
`[hls] autoplay error` and segments arriving with **5–11s gaps**. The 5s buffer drains and the TV WebView
(WebRTC/P2P weaker than desktop Brave) can't refill in time → plays ~5s then rebuffers forever.

**Next-session fixes (try in order):**
1. **Force direct HLS (quick):** block the swarmcloud engine so hls.js plays straight from R2 without P2P
   interception. jsdelivr can't be host-blocked, so add a path needle to `RequestBlocker.DEFAULT_PATH_NEEDLES`:
   `"@swarmcloud"` or `"p2p-engine"` (third-party path match). The player attaches hls BEFORE
   `new P2PEngineHls(...)`, so the engine line just throws and direct playback continues. VERIFY it doesn't
   break attach.
2. **Bump the buffer (quick):** inject into the player frame (guard runs there via `setOf("*")`) JS that raises
   `hls.config.maxBufferLength`/`maxMaxBufferLength` to ~30 once the hls instance exists
   (`player.core.getCurrentPlayback()._hls`).
3. **Durable (recommended): native ExoPlayer.** Extract `mono.m3u8` from the embed and play in the app's
   existing ExoPlayer, bypassing the web player's tiny buffer + P2P + ads entirely. App already has ExoPlayer
   (torrent path). This is the real answer for this class of site.

## (historical) OPEN BUG: plays ~5s then stalls into forever-loading
Live HLS. Master m3u8 (`xameleon.phantemlis.top/.../index.m3u8`) + variant load fine; segments are `IMG_*.png` on a Cloudflare R2 bucket (`*.r2.cloudflarestorage.com`) served as fake images. **NOTE: an earlier build wrongly blocked that R2 bucket as "ad video" and stalled playback — it's the STREAM CDN. It's now unblocked and `cloudflarestorage` is in `SAFE_LABELS`. Do NOT re-block it.**

~5s = the initial segment window buffers, then the next fetch stalls. **Prime suspects (in order):**
1. **My ad-blocking killing continued segment/manifest delivery.** After the first window, hls.js refreshes the m3u8 and/or fetches segments from a host that my `looksRandomAdDomain()` heuristic or `core-hosts.txt` now blocks. R2 (`cloudflarestorage`) is safelisted, but if segments/keys rotate to a *different* random-looking host, the heuristic may eat them. **Check first.**
2. **P2P engine** (`@swarmcloud/hls`, announce `ann.cdn-lab.shop/v1`, token "greek") — if it can't peer and direct-fallback is throttled/blocked.
3. m3u8 token/`X-Amz` expiry, or live-edge drift (less likely — works on Mac).

### How to investigate (re-add these 2 temp diagnostics, then remove)
`BlockingRuntime.intercept()` after `recordLatency(dt)`:
```kotlin
Log.i("KZ_NETDIAG","blk=${result.blocks} r=${result.name} mf=${request.isForMainFrame} url=${url?.take(110)}")
```
`KeenWebChromeClient.onConsoleMessage()` inside the `if (consoleMessage != null)`:
```kotlin
android.util.Log.i("KZ_CONSOLE","${consoleMessage.messageLevel()} ${consoleMessage.message()?.take(200)}")
```
Then: cold start, play, wait ~20s, and at the stall look for:
```bash
adb -s $D logcat -d -s KZ_NETDIAG | grep 'blk=true'   # any STREAM host blocked right at the stall?
adb -s $D logcat -d -s KZ_CONSOLE | grep -iE 'hls|fatal|fragment|level|buffer|stall|cors|404|403'
adb -s $D logcat -d | grep -iE 'c2.amlogic.avc.decoder'  # decoder activity = still playing
```
If a stream host shows `blk=true` at the stall → it's my blocking; add its label to `SAFE_LABELS` or loosen the heuristic / media-path exclusion (note segments here are `.png`, NOT a media extension — `isMediaPath()` won't protect them; the R2 host safelist is what protects them). If NOT blocking → it's P2P/token; test disabling P2P or check hls.js fatal error type.

**Quick triage:** temporarily set `RequestBlocker.looksRandomAdDomain()` to always return false, rebuild, and see if the stall disappears — that isolates "my heuristic broke the stream" from "P2P/stream issue" in one build.

## Key files
- `app/src/main/java/com/keenzero/app/blocking/RequestBlocker.kt` — host match, `looksRandomAdDomain()`, `SAFE_LABELS`, `isMediaPath()`
- `app/src/main/java/com/keenzero/app/blocking/BlockingRuntime.kt` — WebView/SW intercept, `isHostBlocked()`
- `app/src/main/assets/blocking/core-hosts.txt` — blocklist (project ad hosts appended at end)
- `app/src/main/java/com/keenzero/app/navigation/WindowRequestBroker.kt` — popup policy
- `app/src/main/java/com/keenzero/app/web/HostileOverlayGuard.kt` — cosmetic overlay/banner removal (runs in ALL frames via `addDocumentStartJavaScript(..., setOf("*"))`)
- `app/src/main/java/com/keenzero/app/input/RemoteInputRouter.kt` — pointer/activation; ACTIVATE_JS `iframeTouch` fix (~line 2258)
- `app/src/main/java/com/keenzero/app/web/FramePlayerJs.kt` — in-frame player agent (unmute / auto-fullscreen / CSS-fill chain / hls buffer / media bridge). **Anything that must touch the `<video>` on an embed site belongs here, NOT in evaluateJavascript.**

## 🔬 Diagnostic switches (leave OFF for normal use — delete the file + restart)
```bash
D=192.168.68.57:5555
adb -s $D shell touch /data/local/tmp/keen_no_inject   # zero injected JS (all-or-nothing)
adb -s $D shell touch /data/local/tmp/keen_no_guard    # HostileOverlayGuard off
adb -s $D shell touch /data/local/tmp/keen_no_player   # FramePlayerJs off
adb -s $D shell touch /data/local/tmp/keen_no_scroll   # Modal/ScrollAuthority off
adb -s $D shell touch /data/local/tmp/keen_stock_ua    # honest WebView UA (no Chrome spoof)
adb -s $D shell "rm -f /data/local/tmp/keen_*"         # back to production behaviour
```
These bisect toggles are what turned "Cloudflare is broken" into a one-run answer. **Keep them.**

## ⚠️ Still open / unverified
- **hls buffer fix has NEVER engaged** — zero `KZ_HLS_TUNED`/`KZ_HLS_BUMPED` in any session.
  The player is Clappr but the instance is not on `window.Hls` or `player.core.getCurrentPlayback()._hls`,
  and the bounded `Object.keys(window)` scan added in v0.1.109 has not hit either. The 5s
  buffer is therefore still live. **Stop guessing at globals — go for native ExoPlayer (fix #3).**
- **Checkbox under a live challenge is unverified** — after v0.1.126 no challenge has appeared
  on 1337x/ext.to at all, so `method=challengeTouch` has not been observed end-to-end.
- **UA spoof is still ON.** Untested whether the honest UA improves challenge outcomes now that
  the guard is fixed; it is a real trade (the spoof exists because sites degrade WebView clients).
- Diagnostics are still verbose: `KZ_OVL` overlay dumps, page-error console logging, `KZ_NETDIAG`
  3p logging. Trim when the ad situation is confirmed stable.

## Verifying the in-frame agent (console needles, all via logcat)
```bash
D=192.168.68.57:5555
adb -s $D logcat -d | grep -E "KZ_FRAME_|KZ_HLS_|FRAME_PLAY_ADOPTED"
```
| needle | means |
|---|---|
| `KZ_FRAME_UNMUTE:<reason>` | unmute applied (`re-mute` = player tried to re-mute and we won) |
| `KZ_FRAME_FS_REQ:<reason>` | real `requestFullscreen()` issued from the gesture |
| `KZ_FRAME_FILL_FALLBACK` / `KZ_FRAME_FILL:self` | real fullscreen refused → CSS-fill chain used |
| `KZ_HLS_TUNED:<buf>/<sync>` | constructor wrap caught hls.js (best case) |
| `KZ_HLS_BUMPED:<buf>` | bundled player — live instance patched instead |
| `KZ_FRAME_MEDIA:{…}` | state change relayed to the top frame |
| `FRAME_PLAY_ADOPTED` | native adopted the frame play as a PlayIntent (immersive + audio focus) |

If fullscreen shows `KZ_FRAME_FILL_FALLBACK` every time, the iframe chain is refusing the
permission — the fill is cosmetically equivalent, but the site's own controls may be clipped.

## Notes / gotchas
- The device gets a DIFFERENT ad payload than a spoofed `curl` (geo/UA/cookie targeted) — always trace on-device, not via curl.
- Ad hosts rotate every page load (DGA); static lists are same-session only — the heuristic is the durable defence.
- `adb input tap` for verification is unreliable (hits wrong buttons / scrolls / launches torrents). Prefer having the user drive the remote, or screenshot-verify.
- Next feature the user wants after the stall is fixed: **auto-fullscreen on play** (constraint: fullscreen must be triggered inside the tap gesture; player is a cross-origin iframe — hook the play click in the injected in-frame JS).
