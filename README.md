# dumpd

**BrainDump Task Splitter.** Dump a messy idea by voice or text in one tap. Gemini Nano splits it into a title, a concept, tech anchors and exactly 3 micro tasks, all on-device. The app has no INTERNET permission.

## How it flows

1. **Capture.** Use the Quick Settings tile, a launcher shortcut (long-press the icon), or the Dump button. The raw text goes into Room right away and the sheet closes.
2. **Split.** An expedited WorkManager job sends queued ideas through `AICoreManager`, which talks to Gemini Nano via AICore. Strict prompt, then JSON validation, then one stricter retry if the answer doesn't validate.
3. **Act.** The dashboard groups ideas by day. Tap a card to see its 3 checkboxes.

If AICore isn't ready (model still downloading, service busy), the idea stays queued and is retried up to 3 times. It also retries when you open the app. Failed ideas get a Retry button.

## Device requirements

- Android 12+ (SDK 31), arm64.
- AICore with Gemini Nano (Pixel 9 series and other supported devices). The Google AI Edge SDK is **experimental**: AICore may need a system update and access to the experimental Gemini Nano program before it will serve this app. Without it the app still captures and stores ideas, and the dashboard shows why AI is offline.

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
