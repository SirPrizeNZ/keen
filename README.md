<p align="center">
  <img src="assets/logo.png" alt="Keen" width="520">
</p>

<h1 align="center">Keen</h1>

<p align="center">
  <strong>A tiny, fast Android TV browser built for the hostile streaming web.</strong>
</p>

<p align="center">
  <a href="releases/keen-0.1.14-armeabi-v7a.apk"><img src="https://img.shields.io/badge/download-APK%20v0.1.14-111111?style=for-the-badge" alt="Download APK"></a>
  &nbsp;
  <a href="https://github.com/SirPrizeNZ/keen/releases/latest"><img src="https://img.shields.io/badge/github-releases-24292f?style=for-the-badge" alt="Releases"></a>
  &nbsp;
  <a href="LICENSE"><img src="https://img.shields.io/badge/license-MIT-2ea44f?style=for-the-badge" alt="License MIT"></a>
</p>

<br>

Keen is roughly **3 MB** and deliberately engineered to stay lightweight.

**Zero bundled Chrome bloat. No second browser engine. No tabs. No sync. No extensions. No desktop-browser clutter.**

Keen uses the Android System WebView already on your device and adds only what matters on a television.

**Less overhead. Fewer moving parts. Faster startup. A browser built to stay out of the way.**

One job:

**Get from website to playback with a TV remote — without popups, redirects and hostile overlays taking over the screen.**

---

## Download

| | |
|:--|:--|
| **Version** | v0.1.14 |
| **Platform** | Android TV / Google TV · API 29+ |
| **ABI** | **32-bit ARM (`armeabi-v7a`)** — primary release |
| **APK** | **[keen-0.1.14-armeabi-v7a.apk](releases/keen-0.1.14-armeabi-v7a.apk)** |
| **Checksums** | [`SHA256SUMS`](releases/SHA256SUMS) |
| **Release page** | [github.com/SirPrizeNZ/keen/releases](https://github.com/SirPrizeNZ/keen/releases/latest) |

```bash
adb install -r releases/keen-0.1.14-armeabi-v7a.apk
```

No dedicated arm64 package yet. The published build is the 32-bit ARMv7 APK for classic Android TV hardware.

---

## Why Keen?

### Tiny by design

Keen does not ship an entire browser ecosystem just to open a webpage.

**One Activity. One live WebView. One session. Around 3 MB.**

Everything that does not support the core TV journey is removed.

- No bundled Chrome browser  
- No tab-management machinery  
- No account ecosystem  
- No unnecessary background services  

**Just an incredibly lightweight layer between you, the web and your TV.**

<br>

### Popups never get your screen

Keen does more than ordinary ad blocking.

When a website tries to open a new window, Keen can place it inside an invisible **popup quarantine** before it ever reaches your TV.

The destination is inspected first.

**Unwanted popup? Destroyed.**  
**Legitimate playback or login flow? Continued in the main session.**

The popup never gets your screen.

<br>

### It blocks the whole hostile journey

Keen combines multiple layers of protection:

- network ad and tracker blocking  
- service-worker request interception  
- popup quarantine  
- hostile redirect containment  
- external-app escape prevention  
- intrusive overlay removal  
- site-specific repairs  

Traditional blockers mainly ask:

**Should this request load?**

Keen also asks:

**Did the user actually choose to go there?**

That difference matters on a television.

<br>

### Built for a remote, with touch when you need it

Most websites were designed for a mouse or touchscreen.

Keen was built around the TV remote.

It finds interactive elements, moves between them directionally, reaches content outside the visible screen and scrolls targets into view.

Need more direct control?

**Long-press OK to switch between D-pad navigation and touch-style pointer control.**

Use D-pad navigation when the page behaves.

Switch to touch control when it does not.

**One remote. Two ways to control the web.**

---

## One screen. One session. One job.

Keen is not trying to recreate Chrome on a television.

The journey is simple:

**Open → navigate → choose → block the junk → play → fullscreen → return cleanly**

No browser circus.

No endless tabs.

**No popup bullshit.**

Just a cleaner, faster path to what you actually wanted to watch.

---

## Current status

Keen is under active development.

The current release candidate includes:

- lightweight ~3 MB APK  
- Android TV launcher support  
- D-pad navigation  
- long-press OK to switch to touch-style control  
- off-screen target handling  
- popup quarantine  
- redirect containment  
- request blocking  
- hostile-overlay removal  
- fullscreen playback handling  
- performance safeguards  

Broader physical-device testing is ongoing.

---

## The goal

Browsing the web from a television should not feel like fighting the web.

**You chose where you wanted to go.  
Keen helps you get there.**

<br>

<p align="center">
  <strong>Less browser. Better television.</strong>
</p>

---

## License

[MIT](LICENSE)
