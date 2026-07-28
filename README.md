<div align="center">

<br>
<br>

<picture>
  <source media="(prefers-color-scheme: dark)" srcset="assets/logo-dark.svg">
  <img src="assets/logo-light.svg" alt="Keen" width="200">
</picture>

<br>
<br>
<br>

<img src="assets/keen-title.png" alt="Keen" width="150">

<br>

**Keen is an open-source Android TV browser built for hostile streaming sites.**

It blocks aggressive ads and popup chains, unlocks hardware-decoded video and surround sound,
and lets you stream or save magnet links and torrents with one press.

[Beta](#beta) ·
[Stable](#stable) ·
[Installation](#installation) ·
[Contributing](#contributing) ·
[Licence](#licence)

<img src="assets/keen-hero.gif" alt="Keen running on Android TV" width="900">

</div>

---

Keen blocks the ads, popups, redirects and malicious overlays that make browsing on a television unbearable.

When you find something to watch, Keen can hand the media to your TV's hardware player for wider video and audio format support, reliable fullscreen playback and surround sound on compatible hardware.

Open a magnet link or `.torrent` file and press once to start watching. Stream it immediately or save it to the box for offline playback.

All in a free, open-source app of about 18 MB.

No bundled Chromium. No popup maze. No unnecessary bloat.

## Serious ad blocking

Keen does more than apply a basic host list.

Its layered defence blocks:

- Ad and tracker requests
- Service-worker advertising
- Popups and unwanted new windows
- Hostile redirects
- Fake external-app launches
- Intrusive overlays
- Rotating machine-generated ad domains
- Site-specific advertising and playback interference

Keen also considers whether the user actually chose to navigate somewhere.

Legitimate playback, login and verification flows can continue while unwanted popups and redirects are destroyed before they reach the screen.

When a genuine verification challenge cannot work inside the protected browser, Keen can move that site into an isolated compatibility session. The fully protected browser is restored when the user leaves that site.

## Better video and audio playback

Android WebView cannot reliably play every format used by streaming sites.

Keen can intercept supported media and hand it to Media3 and the TV's hardware decoders instead. This enables wider codec support, including formats such as E-AC-3 and DTS, with multichannel and surround sound where supported by the device.

Keen also provides:

- Hardware-decoded playback
- Reliable fullscreen video
- Automatic English subtitles when available
- Playback resume
- Remote-friendly seeking
- Recovery after an app or device restart

## Stream magnets and torrents

Open a magnet link or `.torrent` file and Keen begins downloading the main video sequentially.

Playback starts once enough of the file is buffered. There is no need to wait for the entire download to finish or move between separate browser, torrent and media-player apps.

While the stream prepares, Keen shows live buffering progress, seeders, leechers and download speed.

The temporary streaming cache is removed when the session ends.

## Save with one press

> Available in the current beta.

Press the star in the player to keep what you are watching. The download continues independently in the background and appears in the Downloaded row on the Keen home screen when complete.

Saved titles:

- Play offline from local storage
- Continue downloading when you leave the player
- Are not interrupted when another stream starts
- Leave the swarm when the download completes
- Never seed after completion
- Can be deleted completely by pressing the star again

Streaming and saved downloads run independently, each with its own torrent session.

## Built for Android TV

Keen is designed for a television and remote, not a desktop monitor.

- Remote-first navigation
- D-pad focus across complex websites
- Pointer control when a site requires it
- Favourites on the home screen
- Continue-watching cards
- Address completion from browsing history
- Clean fullscreen playback
- One live WebView instead of a stack of tabs and windows

## No bundled browser engine

Keen uses the Android System WebView already installed and maintained on your device.

It does not package another copy of Chromium inside the APK. This keeps Keen small and avoids the storage, memory and maintenance burden of a second browser engine.

## Beta

The beta contains the newest Keen features and is the recommended build for testing.

**[Download Keen v0.2.0-beta.1](https://github.com/SirPrizeNZ/keen/releases/download/v0.2.0-beta.1/keen-0.2.0-beta.1-32bit-armeabi-v7a.apk)** ·
[Release notes](https://github.com/SirPrizeNZ/keen/releases/tag/v0.2.0-beta.1) ·
[SHA256SUMS](https://github.com/SirPrizeNZ/keen/releases/download/v0.2.0-beta.1/SHA256SUMS)

18.3 MiB · Android TV 10+ · 32-bit ARM (`armeabi-v7a`)

The beta uses the same signing key as the stable release, so it can be installed over stable without losing favourites, history or playback positions.

Expect occasional rough edges.

## Stable

Recommended for users who prefer the most established release.

**[Download Keen v0.1.154](https://github.com/SirPrizeNZ/keen/releases/download/v0.1.154/keen-0.1.154-32bit-armeabi-v7a.apk)** ·
[Release notes](https://github.com/SirPrizeNZ/keen/releases/tag/v0.1.154) ·
[SHA256SUMS](https://github.com/SirPrizeNZ/keen/releases/download/v0.1.154/SHA256SUMS)

18.3 MiB · Android TV 10+ · 32-bit ARM (`armeabi-v7a`)

All published builds are available from [GitHub Releases](https://github.com/SirPrizeNZ/keen/releases).

## Installation

Keen is not currently distributed through the Google Play Store. Download either APK above and sideload it onto your Android TV or Google TV device.

### Install over Wi-Fi with ADB

1. Open Developer options on the television.
2. Enable Wireless debugging or USB debugging.
3. Note the IP address and port displayed by the television.
4. Connect from a computer with Android platform tools installed.
5. Install the downloaded APK.

```bash
adb connect <tv-ip>:<port>
adb install -r <keen-apk-filename>
```

For example, to install the beta:

```bash
adb install -r keen-0.2.0-beta.1-32bit-armeabi-v7a.apk
```

To install the stable release:

```bash
adb install -r keen-0.1.154-32bit-armeabi-v7a.apk
```

Use the exact IP address and port displayed by the television. Wireless debugging may use a port other than `5555`.

The `-r` flag updates an existing installation while preserving its local data.

### Tested hardware

Keen is developed and tested on a Xiaomi MiTV-AFMU0 (twilight) running Android TV 14.

Testing on other Android TV and Google TV devices is welcome.

## Roadmap

- 64-bit (`arm64-v8a`) builds alongside the 32-bit package for newer boxes

## Responsible use

Keen is a general-purpose browser and an open-protocol media client. The same underlying technologies are used to distribute Creative Commons films, Linux images, public-domain archives and self-hosted media.

> [!IMPORTANT]
> Only access content you are legally entitled to. Keen bypasses no DRM and defeats no access controls. It opens the web and open protocols; what you do with them is your responsibility.

## Contributing

Contributions are welcome, including bug reports, site-compatibility fixes, testing on real Android TV hardware, ad-blocking improvements, playback fixes, documentation and Kotlin code.

Open an issue or submit a pull request.

Keen is a single, heavily commented Android module written in Kotlin. The project is intended to remain understandable and approachable rather than becoming another oversized browser codebase.

See [CONTRIBUTING.md](CONTRIBUTING.md) to get started.

## Licence

Keen is free and open source under the [GNU Affero General Public License v3.0](LICENSE).

You are free to use, study, modify and share it. Any modified version you distribute, or run as a network service, must also make its corresponding source code available under the AGPL.
