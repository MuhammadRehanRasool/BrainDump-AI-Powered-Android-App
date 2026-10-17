# dumpd

**BrainDump Task Splitter.** Dump a messy idea by voice or text in one tap. Gemini Nano, the copy that's already on your phone, splits it into a title, a concept, tech anchors and exactly 3 micro tasks. The app has no INTERNET permission.

## How it flows

1. **Capture.** Use the Quick Settings tile, a launcher shortcut (long-press the icon), or the Dump button. Voice runs through the phone's on-device speech recognizer and fills the text box live.
2. **Split.** The idea goes into Room, then straight to Gemini Nano via the ML Kit GenAI Prompt API while the sheet is still open. That usually takes a second or two. Then the sheet closes and a toast shows the title.
3. **Act.** The dashboard groups ideas by day. Tap a card to see its 3 checkboxes.

Gemini Nano only answers apps in the foreground. If you swipe the sheet away mid-split, or the model isn't ready, the idea is kept and split the next time you open the app. Failed ideas get a Retry button.

## Device requirements

- Android 12+ (SDK 31), arm64.
- A phone with Gemini Nano in AICore (Pixel 9/10 series and other supported devices), with AICore and Private Compute Services updated from the Play Store.
- Voice: an on-device speech pack for your language. If it's missing, the app asks the system to download it.

## CI

`.github/workflows/android-build.yml` runs on every push to `main` and uploads an artifact named after the repo:

- `<repo>.apk`: R8-optimized release build. Install this one.
- `<repo>-debug.apk`: unminified debug build, for troubleshooting.

Optional signing secrets (Settings → Secrets and variables → Actions):

| Secret | Value |
| --- | --- |
| `KEYSTORE_BASE64` | `base64 -w0 release.jks` |
| `KEYSTORE_PASSWORD` | keystore password |
| `KEY_ALIAS` | key alias |
| `KEY_PASSWORD` | key password |

Without the secrets, the release APK is signed with a CI debug key. That key is cached, so later builds install over earlier ones.
