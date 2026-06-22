# AudioMicSync

Android app that records audio from a USB microphone and automatically transfers takes to a PC — triggered over Wi-Fi by **[rokoko-audio-sync](https://github.com/KriaaCompany/rokoko-audio-sync)**, Kriaa's companion PC tool that bridges Rokoko Studio with this app.

Built for motion-capture workflows where the Android device acts as a body-worn audio recorder that stays in sync with the capture session.

## How it works

1. **[rokoko-audio-sync](https://github.com/KriaaCompany/rokoko-audio-sync)** runs on your PC and monitors Rokoko Studio for take events
2. It sends UDP packets to this app on the Android device (default port **9877**)
3. On `record_start`, the app begins capturing audio from the selected microphone
4. On `record_stop`, it encodes the PCM buffer to WAV and uploads it back to the PC via HTTP (default port **9878**)

UDP packet format sent by rokoko-audio-sync:
```json
{ "cmd": "record_start", "take": "TAKE_001" }
{ "cmd": "record_stop" }
```

## Features

- USB microphone selection (prioritised over built-in mic)
- Configurable sample rate: 44 100 Hz or 48 000 Hz
- Mono 16-bit WAV output
- Automatic retry on upload failure (3 attempts)
- Persistent foreground service with wake lock and Wi-Fi lock
- In-app event log for debugging

## Requirements

- Android 8.0 (API 26) or later
- A PC running a compatible HTTP receiver on the local network
- Wi-Fi or Ethernet connection shared between phone and PC

## Setup

1. Install the app on your Android device
2. Open the app and grant **Microphone** and **Notifications** permissions
3. Tap the **⋮** menu → **Settings** and enter:
   - **PC IP** — the local IP address of your PC
   - **Upload port** — the HTTP port your PC receiver listens on (default 9878)
   - **UDP port** — the port the app listens on for trigger commands (default 9877)
4. Select your USB microphone from the dropdown (tap **Refresh** after plugging it in)
5. Your phone's IP is shown on the main screen — point your trigger software there

## PC tool

The companion PC app **[rokoko-audio-sync](https://github.com/KriaaCompany/rokoko-audio-sync)** handles both sides:
sending UDP triggers to this app and receiving the uploaded WAV files. See that repo for setup instructions.

## Building

Open the project in Android Studio or build from the command line:

```bash
./gradlew assembleDebug
```

Output APK: `app/build/outputs/apk/debug/app-debug.apk`

## Links

- [Privacy Policy](https://kriaacompany.github.io/android-audio-sync/privacy.html)
- [Terms of Use](https://kriaacompany.github.io/android-audio-sync/terms.html)

## License

MIT © 2026 [Kriaa](https://kriaa.in)
