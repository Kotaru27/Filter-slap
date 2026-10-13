# Filter Slap — audit and release gates (2026-10-13)

## Release status: NOT VERIFIED / NOT AN INSTALLABLE BUILD

This project is an engineering update, **not a tested app release**. No updated APK was built from these sources. The original APK, and the older binary-hotfix APKs, DO NOT contain all these changes.

No Android SDK, Android Gradle distribution, Android device, or emulator was available in the execution environment. External Gradle download resolution also failed. Consequently Gradle/Compose/Robolectric compilation, debug APK signing, installation, runtime gallery I/O, and on-device image processing were **not** executed. A JDK/Kotlin host compile with API stubs is not a substitute.

## Verified by local host checks (limited evidence)

- The engine's `ImageAnalyzer`, `ContextualDecisionEngine`, `ImageProcessor`, `ProcessingSafetyMath` source, **and all 14 engine test classes**, compiled under Kotlin 1.9 against local test doubles for Android graphics, BoofCV, Robolectric and JUnit APIs. This checks source-level syntax/types against stubs, not actual Android/BoofCV APIs.
- 29 synthetic end-to-end host render smoke scenarios completed without source bitmap mutation, dimension change, or introduced highlight clipping under the smoke gate. These test fixtures are artificial and not photographs.
- 10,126 randomized/edge-case property checks passed, covering bounds and crop, finite magnitude, source preservation, safety attenuation, and analogous invariants.
- 147 methods passed in the **mocked** JUnit runner across 11 classes: BackgroundDeemphasis, ContextualDecisionEngine, GradingProfile, HueBandRefine, ProcessingSafetyMath, SplitTone (52); ImageAnalyzer, ImageProcessor and ImageForensics (71); FidelityGuard (5); PipelineIntegration (13); PerformanceBaseline (6).
- MainActivity source was parsed by `kotlinc` without parser errors, but **not type-checked against Compose/Android** (those unresolved imports are expected in the parser-only check).

## Failures and gaps — do not hide

- **Known failed test:** `AdaptiveRefinementTest.genuinely drab foliage recovers saturation` fails in the mocked runner. Mean normalized RGB saturation goes from ~0.2146 to ~0.0949. This failure is reproducible in the **previous v2 engine** with the same mock runner, so it is a pre-existing bug, not evidence that v3 fixed it. Global green-tint white balance and foliage saturation enhancement can conflict.
- **Important ambiguity:** A uniform naturally green surface and a neutral surface illuminated by green light can have the same RGB pixels. The current pixel-statistical engine cannot reliably resolve this without an independent neutrality reference or stronger semantic evidence. The legacy test that expects automatic correction of an isolated cast-colored gray card assumes scene knowledge not encoded in the image. Do not add RGB/photo-specific exceptions to make those conflicting expectations pass.
- `PhotoRegressionTest` stalled in this host/mock runner and did not finish. Its results must not be reported as passing. `AdaptiveRefinementTest` and `PhotoRegressionTest` are not included in the 147 passing focused checks.
- No complete, real Gradle `testDebugUnitTest` or Android APK build occurred; JUnit selected mocked tests and the stubbed BoofCV implementation are only supplementary developer diagnostics.
- No objective or blind visual-quality evaluation on diverse held-out real photographs; no proof of quality improvement or no regressions in actual photographs.
- No Android/device verification for picker, crop, lifecycle, cancellation, history, EXIF orientation, storage, saving or installation.

## Known limits

- Heuristic subject/scene masks are not trained semantic segmentation. Skin/sky/vegetation classification can be wrong, especially under colored lighting.
- Final output longest side remains bounded to 4096 px and may be lower on memory-limited devices; some EXIF metadata is not copied to the edited JPEG.
- Synthetic clipping/hue gates do not establish aesthetic or professional quality.
- Entire-image raster processing can be slow/memory-intensive at 4096 px; cancellation is cooperative at coroutine boundaries, not instant during a synchronous pixel loop.
- Edit history is a list of summaries, not a full reopen-and-reedit workflow.

## Non-negotiable release acceptance gates

1. Provide an SDK/Gradle-capable CI runner and execute `./gradlew testDebugUnitTest assembleDebug` successfully; review every failing test, including contradictory white-balance assumptions; no disabling or rewriting tests simply to claim green.
2. Verify the newly compiled APK's signature and native alignment, install on Android 7+ and current 16-KB-page devices, and test real import → edit → compare → crop → save → gallery → history flows.
3. Run a representative **held-out** real-photo benchmark and independent blind review including neutral gray reference scenes, natural colored surfaces, mixed lighting, faces/skin diversity, already-good/artistic edits, night, shadows and high-contrast areas. No per-image conditions or hardcoded photo values.
4. Document remaining failures, fix generalized causes without sacrificing known-good behavior, and repeat the entire benchmark before calling the update complete.

See `CHANGELOG-V3.md` for change scope. Run `.github/workflows/build-debug-apk.yml` on a configured GitHub repository to test/build a fresh APK; the workflow has not been executed in this environment.
