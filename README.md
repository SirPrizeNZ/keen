<p align="center">
  <img src="assets/logo.png" alt="Keen" width="480">
</p>

<p align="center">
  <img src="assets/keen-title.png" alt="Keen" width="150">
</p>

<p align="center">
  <strong>A lightweight Android TV browser built for the hostile streaming web.</strong>
</p>

<p align="center">
  <a href="https://github.com/SirPrizeNZ/keen/releases/download/v0.1.81/keen-0.1.81-32bit-armeabi-v7a.apk"><img src="https://img.shields.io/badge/download-APK%20v0.1.81%20%C2%B7%2032--bit-111111?style=for-the-badge" alt="Download Keen v0.1.81 APK"></a>
  &nbsp;
  <a href="https://github.com/SirPrizeNZ/keen/releases/latest"><img src="https://img.shields.io/badge/github-releases-24292f?style=for-the-badge" alt="GitHub Releases"></a>
  &nbsp;
  <a href="LICENSE"><img src="https://img.shields.io/badge/license-MIT-2ea44f?style=for-the-badge" alt="MIT License"></a>
</p>

---

## Stream from the browser — now including torrents

Keen turns touch-first, popup-heavy streaming sites into a remote-controlled TV journey. Open a site, choose what you want, block the junk and play.

**Since v0.1.80:** activate a magnet link or `.torrent` download in the browser and Keen starts streaming the largest video directly in its TV-native player. You can also paste a magnet URI into the address bar.

The torrent engine runs in a separate process, downloads sequentially, serves only over a loopback HTTP bridge and deletes its cache when playback ends. A loading screen reports peers, speed and buffering progress while the stream starts.

> Only stream content you are legally permitted to access. Keen does not bypass DRM or access controls.

## Lightweight by design

Keen does not bundle Chromium or a second browser engine. It uses Android System WebView already on the device and adds a focused TV control, blocking and playback layer.

The signed ARMv7 APK is **18.3 MiB**. Most of the increase from earlier ~3 MB builds is the bundled native ARMv7 torrent engine; the browser architecture remains deliberately small.

- one Activity and one live WebView
- no tabs, sync, extensions or account ecosystem
- no bundled desktop-browser engine
- a separate torrent process so the browsing heap stays lean
- continuity checkpoints and memory-pressure cleanup for low-memory TV hardware

## Download

| | |
|:--|:--|
| **Version** | v0.1.81 (`versionCode` 101) |
| **Platform** | Android TV / Google TV · Android 10+ (API 29+) |
| **ABI** | **32-bit ARM (`armeabi-v7a`)** |
| **APK** | **[keen-0.1.81-32bit-armeabi-v7a.apk](https://github.com/SirPrizeNZ/keen/releases/download/v0.1.81/keen-0.1.81-32bit-armeabi-v7a.apk)** |
| **Checksum** | [`SHA256SUMS`](https://github.com/SirPrizeNZ/keen/releases/download/v0.1.81/SHA256SUMS) |
| **Release notes** | [Keen v0.1.81](https://github.com/SirPrizeNZ/keen/releases/tag/v0.1.81) |

**Install over Wi-Fi**

1. On the TV, enable USB debugging and network/wireless debugging in Developer options.
2. Note the TV's IP address and debugging port.
3. From a computer with Android platform tools:

```bash
adb connect <tv-ip>:5555
adb install -r keen-0.1.81-32bit-armeabi-v7a.apk
```

Accept the debugging prompt on the TV if it appears. Wireless debugging may show a port other than `5555`; use the port displayed by the TV.

The published build is the 32-bit ARMv7 APK for classic Android TV hardware. There is no dedicated arm64 package yet.

## Ad blocking that protects the whole journey

Keen combines several layers rather than relying on a single request filter:

- network ad and tracker blocking
- service-worker request interception
- popup quarantine before a new window reaches the screen
- hostile redirect containment
- external-app escape prevention
- intrusive overlay removal
- site-specific playback and navigation repairs

Traditional blockers mainly ask, "Should this request load?" Keen also asks, "Did the user actually choose to go there?"

Legitimate playback and login flows can continue in the main session. Unwanted popups are destroyed without ever taking over the television.

## Built for a remote

Keen finds interactive elements, moves between them directionally, reaches content outside the visible screen and scrolls targets into view.

Long-press **OK** to switch between D-pad navigation and touch-style pointer control. Use directional navigation when the page behaves; use the pointer when it does not.

Playback uses D-pad and media controls, supports fullscreen, and returns cleanly to the page underneath.

## What Keen includes

- direct magnet and `.torrent` streaming in the browser
- native TV playback through Media3/ExoPlayer
- layered ad, popup, redirect and overlay defence
- improved pointer precision and horizontal/vertical remote scrolling
- playback priority plus durable session restoration after low-memory kills
- Android TV launcher support for API 29+ ARMv7 devices

v0.1.81 is a packaging and repository-curation release only; app behavior is unchanged from v0.1.80.

## One screen. One session. One job.

```text
Open → navigate → choose → block the junk → play → fullscreen → return cleanly
```

Keen is not trying to recreate Chrome on a television. It is a cleaner, faster path to what you actually wanted to watch.

<p align="center">
  <strong>Less browser. Better television.</strong>
</p>

## License

[MIT](LICENSE)
