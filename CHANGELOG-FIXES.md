# Filter Slap — fidelity and reliability patch

## Scope
Preserves the native Kotlin/Jetpack Compose app, its one-tap workflow, architecture, and current edit-profile logic. No ML model or cloud features were added. Original testing photos, caches and binary build outputs are intentionally excluded.

## Changes

1. **RGB luma / HSL lightness error:** Detail and luma-noise processing now transfer *only their luminance delta* to HSL lightness. Saturated yellows are no longer replaced by their much brighter RGB luminance.
2. **HSL hue drift:** Hue is compared in degrees over the shortest circular arc and normalized against 180 degrees; the previous threshold assumed a [0,1] hue range.
3. **Harm throttle:** Stronger damage now produces stronger throttling, fixing the reversed `1 - harm` formula. Regional tone and all color-affecting edit passes are also eligible for guard attenuation.
4. **Safety previews:** The safety preview now applies the same WB, OKLab, split-tone and hue-refinement post-passes as the final renderer. Re-check runs up to five attenuation rounds after convergence.
5. **Highlight and shadow harm:** The comparison checks newly harmed *pixels*, not only the net number of clipped pixels, which could hide new blown areas when other areas improved.
6. **Output fail-safe:** The actual final output is sampled after all passes. Catastrophic clipping or color damage falls back to an untouched original and a truthful empty applied plan. The sampling heuristic is not a formal photographic-quality guarantee.
7. **Detail noise reduction:** High-frequency noise attenuation is applied relative to an unmodified frequency reference instead of canceling itself out.
8. **No-op preservation:** If the engine finds no edits and no measurable noise, it returns an unchanged copy instead of running a lossy round-trip.
9. **Memory:** Removed an unused full-image float array from the detail pipeline (about 4 bytes per pixel saved).
10. **Async correctness:** Picking another image or discarding now cancels in-flight decode/processing jobs, preventing stale results from being published. A save request captures its own preview bitmap, profile, and notes instead of looking up a different photo later.
11. **Comparison:** Hold-to-compare uses the selected crop frame.
12. **Gallery export:** A missing output stream or failed JPEG compression is treated as failure and the incomplete MediaStore entry is cleaned up.
13. **Build layout:** Restored Gradle wrapper files and version catalog from `Originals/gradle/` to `gradle/` where Gradle expects them.
14. **Regression coverage:** Added four JUnit methods for detail color preservation, luma noise preservation, degree-wrapped hue distance and monotonic harm throttling.

## Validation completed in this environment
- Kotlin `ProcessingSafetyMath.kt` compiled independently using `kotlinc 1.9.0`.
- The **actual edited ImageProcessor.kt** compiled with Kotlin 1.9.0 against local API stubs, covering syntax and source-level type checking of the edited engine. API stubs are not real Android framework/library implementations.
- 7 fixed assertions, 10,001 harm-throttle samples and 30,000 randomized color-math invariants passed in standalone Kotlin checks.
- Full Gradle build and Robolectric tests **NOT RUN**: Gradle 9.3.1 download fails with `UnknownHostException: services.gradle.org` and the Android SDK is unavailable.
- No emulator/device run and no representative real-photo output evaluation completed.

## How to produce the ACTUAL patched debug APK
Open this folder in Android Studio with Android SDK 36.1, JDK 17+ and working dependency downloads. Run:

```bash
./gradlew testDebugUnitTest assembleDebug
```

On Windows:

```powershell
.\gradlew.bat testDebugUnitTest assembleDebug
```

The freshly compiled APK is at `app/build/outputs/apk/debug/app-debug.apk`.
The existing GitHub Actions workflow can run tests then upload the debug APK as an Actions artifact after this source is committed to a configured repository.

## Remaining limitations
- A 4096-pixel longest-side cap remains on high-resolution decode/save; it is not a native-dimension full-resolution export for very large originals.
- No trained subject model; scene classification and masking remain heuristic.
- No benchmark or blind review of a representative collection of real images has been completed.
- Small Android UI fixes are source-reviewed but not compiled against Compose in this environment.
- Treat this as a source patch, NOT a verified ready-to-install replacement APK. The original APK provided by the user is unchanged.
