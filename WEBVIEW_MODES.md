# Keen — WebView modes

Keen runs pages in one of two WebView configurations. Only one exists at a time.

## Normal mode

The default for every site. `WebViewHost` + `HardenedWebSettings`:

- host and path blocking (`BlockingRuntime`), service-worker interception
- popup broker with hidden quarantine (`WindowRequestBroker`)
- hostile overlay guard, in-frame player agent, modal scroll — injected at document start
- D-pad driven by `RemoteInputRouter` (indexes and activates elements via injected JS)
- user agent with the `wv` token stripped

## Compatibility mode

For origins that cannot pass a verification challenge in normal mode. A separate
instance (`CompatibilitySession`), never the normal WebView with protections turned off:

- stock user agent (including `wv`) and stock client hints, unchanged for the session
- no injected JavaScript, no JS bridge, no request interception
- popups allowed only to the challenge platform, hidden and time-limited
- hardware accelerated, normal first- and third-party cookies
- D-pad driven natively (`CompatibilityRemoteController`): a cursor `View` above the
  page, real `ACTION_DOWN`/`ACTION_UP` at the cursor, `WebView.scrollBy`. Chrome-bar
  controls are resolved before any touch event is built, so the page never sees them
- magnet links **and `.torrent` downloads** route to native torrent streaming, as in
  normal mode

Entering destroys the normal host; leaving destroys the session and rebuilds it. No
shared flags, no shared `WebSettings`.

### The one scripting exception

A `.torrent` download runs a single `evaluateJavascript` to read the file through the
page, then hands the bytes to the streaming service. Nothing is installed — no
document-start script, no bridge — and it fires on an explicit user action long after the
challenge has cleared, so the environment a challenge inspects at load time is unchanged.

It is not optional. Clearance is bound to the TLS fingerprint, header order and the client
hints in `critical-ch`, none of which `HttpURLConnection` in the `:torrent` process
reproduces — so refetching the URL natively with the copied cookie returns the challenge
page, not the torrent. **Anything that needs bytes from a challenged origin must come
through the page.** See `TorrentDownloadIntercept`.

> Compatibility mode has **no ad blocking, popup broker or overlay guard**. That is the
> cost of presenting an unmodified environment, and why it is never the default.

## How an origin qualifies

`ChallengeLoopDetector` watches normal mode and promotes an origin when:

- a main-frame `403`/`503` carries genuine Cloudflare headers (`cf-ray`, `server`) —
  page wording is never trusted, or a hostile interstitial could disable blocking; **and**
- the challenge repeats once, or produces no content for 4s

The switch is automatic and silent, typically before a spinner appears.
`CompatibilityOriginStore` persists the decision, expires it after 14 days so
protections return if a site stops needing this, and refuses origins in `PINNED_NORMAL`.

## Runtime switches

Files in `/data/local/tmp`, read into a cached snapshot at WebView creation. All absent
= stock behaviour. They disable protections **globally** while set — for bisecting only.

| Flag | Effect |
|:--|:--|
| `keen_no_compat` | Force every origin back to normal mode |
| `keen_no_router_js` | Native D-pad in normal mode (no indexing JS) |
| `keen_no_inject` | No document-start scripts |
| `keen_stock_ua` | Keep the `wv` token |
| `keen_no_blocking` | No request interception |
| `keen_no_sw_intercept` | No service-worker interception |
| `keen_no_popup_broker` | Plain `WebChromeClient` |
| `keen_add_router_js` | Inject the D-pad indexing JS *into* compatibility mode |
| `keen_reset_verification` | Clear the origin's challenge cookies on session start |

```bash
adb shell touch /data/local/tmp/<flag>     # on
adb shell rm -f  /data/local/tmp/<flag>    # off
adb logcat -s KZ_EXPERIMENT KZ_CHALLENGE KZ_COMPAT KeenTorrent
```

Known promotions seen on the Mi Box: `ext.to` reaches `repeat_x2` on `http_403` within
two loads and lands in compatibility mode, where it clears (`cfClearance=true`). Torrent
indexes are the common case for this, which is why `.torrent` handling has to work here.

`KZ_EXPERIMENT` prints the active profile on every WebView creation.

## Why it is a separate instance

Removing Keen's surfaces from the normal WebView one at a time never fixed the loop —
not the injected D-pad, the document-start bundle, or service-worker interception. A
stock instance did, and the result follows the setting rather than the clock: on one
origin and IP, normal looped, compatibility passed, normal looped again within four
minutes. The cause is cumulative, so isolation is the fix, not a subset of toggles.
