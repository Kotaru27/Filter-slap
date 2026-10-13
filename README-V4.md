# Filter Slap V4 — release status: BLOCKED

The Android source has an updated conservative color-decision guard. This repository includes the original one-tap editing UI and all tests. **There is no V4 APK in this ZIP**; the real Gradle build could not execute in the author's environment due missing Android SDK/Gradle and blocked distribution downloads.

- Engineering findings and remaining defects: [VALIDATION-V4.md](VALIDATION-V4.md)
- V3 fixes retained: [CHANGELOG-V3.md](CHANGELOG-V3.md)
- Hosted build/test workflow: `.github/workflows/build-debug-apk.yml`

Build from repo root on an Android-capable machine or GitHub Actions:

```bash
./gradlew testDebugUnitTest assembleDebug
```

The current synthetic 'uniform gray card' white-balance tests are expected to fail by design; see report. Don't treat the candidate APK as a verified release just because it builds. The original test images shown in conversation are neither included nor incorporated into production logic.
