# AA Browser

[![Android 15+](https://img.shields.io/badge/Android-15%2B-3DDC84?logo=android&logoColor=white)](https://www.android.com/)
[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](LICENSE)
[![Latest release](https://img.shields.io/github/v/release/sBuBle/AABrowser?label=release)](https://github.com/sBuBle/AABrowser/releases/latest)

A WebView-based web browser for Android Auto head units, for use by passengers or while parked.

This repository is a maintained fork of [kododake/AABrowser](https://github.com/kododake/AABrowser). It adds a built-in ad blocker, pop-up and redirect blocking, more reliable audio and video streaming, a phone layout with a bottom toolbar, tabs shared between the phone and the car screen, security hardening and in-app updates. See [CHANGELOG.md](CHANGELOG.md) for details.

<p align="center">
  <img width="80%" src="docs/img/aabrowser3.gif" alt="AA Browser running on an Android Auto display" />
</p>

## Features

- **Made for car screens:** Material 3 Expressive interface with large touch targets, a layout that adapts to wide car displays, and light, dark and true-black themes.
- **Video and full screen:** plays DRM-protected video (Widevine L3) and switches to full screen with one tap.
- **Ad and tracker blocking:** uses the EasyList, EasyPrivacy, Peter Lowe's and HaGeZi Light filter lists, updated automatically. It hides ad slots, skips YouTube video ads and can be turned off per site.
- **Pop-up and redirect blocking:** pages can only open windows or go to another site when you tap a link. Blocked attempts can be allowed per site.
- **Reliable streaming:** audio and video recover after the connection drops (tunnels, Wi-Fi to mobile handover), and pages that failed offline reload when the connection returns.
- **Tabs:** open, switch, close and restore tabs. The phone and the car screen share the same tabs.
- **Start page:** up to six quick-link tiles with drag-and-drop reordering and an optional custom background.
- **Controls:** one-tap switch between mobile and desktop sites, user agent profiles (including a custom one), interface scaling and bookmarks.
- **In-app updates:** downloads only the changes since the installed version (about 1 MB instead of about 10 MB) and installs them.

## Screenshots

<p align="center">
  <img width="44%" src="docs/screenshots/litemode-v3_0.png" alt="Light theme" />
  <img width="44%" src="docs/screenshots/darkmode-v3_0.png" alt="Dark theme" />
</p>

## Safety

AA Browser is intended for passengers and for use while the vehicle is parked. **Do not use it while driving.** You are responsible for using it safely and in line with local law. As stated in the GPLv3 license, the software is provided without any warranty.

## Installation

Requires Android 15 or later.

1. Download the latest APK from [Releases](https://github.com/sBuBle/AABrowser/releases/latest) and install it.
2. Enable unknown sources in Android Auto:
   1. Open **Settings → Connected devices → Android Auto** on your phone (or search for "Android Auto" in Settings).
   2. Scroll to the bottom and tap **Version** ten times, then tap **OK** to enable developer mode.
   3. Open the menu (⋮) in the top-right corner and choose **Developer settings**.
   4. Turn on **Unknown sources**.
3. Connect your phone to the car. AA Browser appears in the Android Auto launcher.

After installing, use **Check for Updates** in the app's menu to get new versions. You can also track releases of this repository with [Obtainium](https://github.com/ImranR98/Obtainium).

> [!NOTE]
> Releases from this repository are signed with a different key from the original project's releases. To switch from the original app or from a debug build, uninstall it first.

## Troubleshooting

**The app does not start.** Open a navigation app other than Google Maps (for example Waze) first, then open AA Browser.

## Privacy

- The browser does not collect the pages you visit, your searches or what you type.
- Backups and device-to-device transfers do not include cookies or site data.
- The app has no analytics or telemetry.
- The update check contacts the GitHub API for this repository.

## Building from source

Requirements: JDK 21 and the Android SDK (compile SDK 37).

```bash
git clone https://github.com/sBuBle/AABrowser.git
cd AABrowser
./gradlew assembleDebug
```

The debug APK is written to `app/build/outputs/apk/debug/`. Debug builds use a separate application ID, so they install alongside the release app.

Run the unit tests with:

```bash
./gradlew testDebugUnitTest
```

## Contributing

Bug reports and pull requests are welcome. Please describe how to reproduce a bug, and keep pull requests focused on one change.

## Credits

AA Browser was created by [kododake](https://github.com/kododake) with contributions from [Colin Crowther](https://github.com/cmacrowther) (original design), [jigneshbhavani](https://github.com/jigneshbhavani) (microphone support), [SaveEditors](https://github.com/SaveEditors) (multi-tab support, original home screen), [gregorixG](https://github.com/gregorixG) (desktop site user agent handling) and [breakzplatform](https://github.com/breakzplatform) (custom user agent handling).

## License

AA Browser is licensed under the [GNU General Public License v3.0](LICENSE).
