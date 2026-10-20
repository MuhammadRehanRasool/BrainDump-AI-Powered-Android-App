# dumpd

**BrainDump.** Dump rough thoughts by voice or text in one tap. Gemini Nano, the copy that's already on your phone, rewrites them as a clear, polished Markdown note with a title and a category. The app has no INTERNET permission.

## How it flows

1. **Capture.** Use the Quick Settings tile, the home-screen widget (resizes from a single mic button up to a 2x2 card), a launcher shortcut (long-press the icon), or the Dump and mic buttons. Voice runs through the phone's on-device speech recognizer and fills the text box live.
2. **Polish.** The dump goes into Room, then straight to Gemini Nano via the ML Kit GenAI Prompt API while the sheet is still open. Then the sheet closes and a toast shows the title. If the active Nano build returns nothing, the other builds AICore has installed are tried, and the one that works is used from then on.
3. **Read and refine.** The dashboard groups notes by day. Tap a card to read the whole note, chat with Gemini Nano to change it, or share it as Markdown. Settings has export, shortcuts, and every prompt and limit the model runs with.

Gemini Nano only answers apps in the foreground. If you swipe the sheet away mid-polish, or the model isn't ready, the dump is kept and polished the next time you open the app. Failed ideas get a Retry button.

## Device requirements

- Android 12+ (SDK 31), arm64.
- A phone with Gemini Nano in AICore (Pixel 9/10 series and other supported devices), with AICore and Private Compute Services updated from the Play Store.
- Voice: an on-device speech pack for your language. Packs exist per region (en-US, not en-PK), so the app uses an installed pack in your language before asking the system to download one.

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
