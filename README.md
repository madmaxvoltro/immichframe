# Immich Frame

A replacement launcher for Frameo-style Android photo frames (built for a Denver PFF-1027, Android 6). It shows photos from your own [Immich](https://immich.app) server.

- Slideshow in landscape or portrait (follows the rotation sensor). In landscape, two portrait photos are paired side by side.
- Overlays: clock, date, weather (Open-Meteo), place, date and people, album name.
- Smart shuffle: likes, "show less", skips and "on this day" adjust how often a photo comes up.
- Remote control and setup from any browser on the same network: `http://<frame-ip>:8080`
- HTTP API: `http://<frame-ip>:8080/api/...` (status, now, play, ask, like, less, hide, next, prev, settings, display, update…)
- Updates itself from GitHub Releases.

## First install

1. Turn on ADB on the frame and connect it over USB.
2. Run `.\gradlew.bat assembleRelease`, then `adb install -r app\build\outputs\apk\release\app-release.apk`.
3. Remove the stock Frameo app: `adb shell pm uninstall -k --user 0 net.frameo.frame`
4. Optional, for silent updates (needs no accounts on the frame): `adb shell dpm set-device-owner dev.immichframe/.AdminReceiver`
5. Scan the QR code on the frame, or open `http://<frame-ip>:8080/setup`, and enter your Immich URL and API key.

## Releasing an update

```powershell
.\release.ps1 -Notes "what changed"
```

This bumps the version, builds, tags, pushes and creates a GitHub release with the APK. Frames check every 6 hours. To update right away, press **Check for updates** on the remote, or `POST /api/update`.

> Keep `keystore/frame.keystore` backed up. It is not in git, and without it new versions can't install over the old one.
