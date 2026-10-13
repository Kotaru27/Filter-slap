**V7 offline selective-enhancement beta:** [Implementation](README-V7.md) · [Verification](VALIDATION-V7.md). Bundled color and salient-object models; one recipe for preview and export. Photographic-quality limits are documented.

> **V4 engineering update — NOT AN APK RELEASE.** Read [VALIDATION-V4.md](VALIDATION-V4.md) before attempting to build.

# Filter Slap

An **offline, automatic photo editor** for Android. Pick a photo; the engine forensically
analyzes it (tone, color, noise, lens defects, scene regions), attempts to infer correctable defects, renders the edit with region-aware masking, and
saves an edited copy (up to 4096 pixels on its longest edge). No filters to choose, no sliders, no network.

**Release warning:** The updated source has **not been built into an APK**, and the complete test suite is **not green**. The existing engine has a reproducible foliage/color regression. Host-only tests use fake Android and image libraries; they are not production verification. See `VALIDATION-STATUS.md` and `CHANGELOG-V3.md`.

## Requirements

- **JDK 17** (AGP 9.x requires it)
- Android SDK (API 36); set `sdk.dir` in `local.properties` or use Android Studio
- A device/emulator on API 24+ (Android 7.0)

## Build & install

```powershell
.\gradlew.bat assembleDebug          # debug APK (auto-signed)
# APK: app\build\outputs\apk\debug\app-debug.apk
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

Release build needs `KEYSTORE_PATH`, `STORE_PASSWORD`, `KEY_PASSWORD` environment
variables pointing at your upload keystore.

## Tests

The repository includes about 193 unit-test methods (fidelity, regression, forensics,
grading profiles, performance). **Some tests currently fail or are unverified.** Run them with:

```powershell
.\gradlew.bat testDebugUnitTest
```

CI is configured to run the same suite on every push and only upload the APK if it's green
(see `.github/workflows/build-debug-apk.yml`).

## How the engine works

1. **Analyze** (`ImageAnalyzer`): histograms, tonal percentiles, noise/blur/SNR, color
   forensics (neutral-anchor white balance, chroma noise, hue stability), lens forensics
   (radially-symmetric vignette, chromatic aberration), and per-pixel region masks
   (sky, vegetation, skin, subject, background, shadows, highlights, texture).
2. **Decide** (`ContextualDecisionEngine`): picks a grading profile (Portrait /
   Landscape / Low-light / General), declares only genuine photographic defects
   (detection is never permission), respects creative intent (low-key/high-key are
   preserved), and emits a bounded plan.
3. **Render** (`ImageProcessor`): applies the plan with region masks and frequency
   separation (clarity/texture), harm-gates any clipping/crushing on a preview,
   converges the tonal plan by re-analyzing its own render, then runs white balance
   and OKLab chroma grading. The *applied* plan is returned to the UI so the Edit
   Report describes exactly what was rendered.

## Project layout

```
app/src/main/java/com/example/
  MainActivity.kt            UI: welcome → editor → hold-to-compare, report, history
  engine/ImageAnalyzer.kt    forensic measurement
  engine/ContextualDecisionEngine.kt   the "senior editor" decision engine
  engine/ImageProcessor.kt   pixel-level renderer + harm gates + convergence
  db/AppDatabase.kt          Room edit history (bounded at 50 rows)
app/src/test/java/           unit tests (Robolectric)
```

## Notes

- Storage: saves to `Pictures/` via MediaStore. On Android 7–9 the app requests
  legacy write permission at save time; Android 10+ uses scoped storage.
- Personal photos used for testing live in `Originals/` — gitignored, never committed.
