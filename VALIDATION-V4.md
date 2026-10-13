> **CI update 2026-10-13:** A later cloud build DID compile and assemble a signed V4 APK candidate at [run 38421009267](https://github.com/Kotaru27/Filter-slap/actions/runs/38421009267). The first real Android CI run executed 199 unit tests with four failures caused by contradictory uniform-color assumptions. See `CI-TEST-DIAGNOSIS.md` for the narrow test-fixture correction and its verification status. The remaining content of this document is the *historical pre-CI assessment*, not the latest build status. The candidate is not device-tested or quality-approved.

# Filter Slap V4 — audited engineering status (2026-10-13)

## NOT A RELEASE — NO NEW APK EXISTS

A new Android debug APK **could not be built** in this environment. Re-running the actual `./gradlew assembleDebug --no-daemon` command failed while fetching the Gradle distribution: `java.net.UnknownHostException: services.gradle.org`. No Android SDK or Gradle distribution is installed here. Consequently **no compiled V4 Android binary, device installation, Compose compile, Robolectric run, MediaStore save verification, or photographic-quality sign-off exists**. Never distribute an older debug APK under a V4 label.

## Code changes from V3

- **Conservative white-balance evidence:** add `ColorAnalysis.chromaDispersion`, the standard deviation of two RGB chromaticity-opponent components normalized by total channel intensity. This distinguishes chromatic variety from mere changes in exposure. When a frame has nearly uniform chromaticity, global white-balance warmth and tint corrections abstain: the pixels cannot distinguish a colored object from a neutral object illuminated by tinted light. This is a general property for all images (not a hardcoded hue, location, filename, or tested photograph), but the confidence threshold `0.015` remains a heuristic requiring held-out photo validation.
- Three new regression tests for uniform chromaticity, brightness-only variation, and actual chromatic diversity; original tests retained (not silently disabled).
- CI workflow now assembles a **separately labeled, unverified APK candidate** even if unit tests fail, uploads test reports, then explicitly fails the workflow when unit tests are red. It checks APK signature and 16-KB native alignment where an APK exists.
- Gradle/AGP pairing checked against published AGP 9.1.1 compatibility (Gradle 9.3.1/JDK 17/SDK Build Tools 36.0.0); the KSP plugin pin is updated to 2.3.10 per KSP compatibility documentation. Removed an unnecessary Foojay toolchain resolver plugin.

## Real execution in the local mock runner

Kotlin engine source and all 15 engine test classes were compiled against local Android/BoofCV/JUnit stubs using the host Kotlin compiler. This checks syntax and approximate host behavior **only**, not the Android build. Tests were executed as class groups and logs are recorded under `verification/v4/`.

- **192 passed, 4 failed** in selected mocked host runner (including the previously stalled `PhotoRegressionTest`, which completed 41/41 in this run).
- **29/29** synthetic engine smoke scenarios completed in the host runner.
- **10,126** deterministic/randomized property checks passed in the host runner.
- `AdaptiveRefinementTest` improved from V3's 4 pass / 1 failure to V4's **5 pass / 0 failure**.
- `ChromaticAmbiguityTest` new: **3 pass / 0 failure**.
- `ImageForensicsTest`: **17 pass / 4 failure**; the other runnable classes had no failures in this mocked environment.

## Four remaining legacy test failures: explicit design conflict, NOT silently fixed

1. `ImageForensicsTest.green cast is measured and neutralized via the tint decision`
2. `ImageForensicsTest.magenta cast produces a positive tint decision`
3. `ImageForensicsTest.single-light frame keeps white balance available`
4. `ImageForensicsTest.warm gray-card cast is corrected via neutral anchors`

Each failed expectation assumes the fixture's **uniform color is a known neutral card**. Pixels alone do not provide that semantic fact. A uniformly colored wall/paint, uniform colored object, and a color-shifted neutral card can produce the same input. The new conservative behavior deliberately abstains, making these expectations incompatible with the no-overcorrection contract. The original tests remain in source to make this conflict visible; they were not modified just to produce green CI. A definitive product decision or trusted neutrality cue (e.g. explicitly measured card reference) is needed before treating uniform-color corrections as requirements. Do not claim all tests pass.

## Release blockers

1. Provision an Android/Gradle-capable runner and execute `./gradlew testDebugUnitTest assembleDebug`. The CI now reports test failure while still exposing any compiled candidate as an artifact.
2. Decide white-balance tradeoff using physically valid held-out scenes/reference metadata, not the image-specific test fixtures. Validate no-edit behavior against actual subject matter and color casts.
3. Install debug APK on representative Android phones, including 16-KB page-size support where relevant. Verify picker → process → original comparison → crop → save → gallery → history and app lifecycle/error cases.
4. Benchmark independent real photographs for tone, color, halos, skin, selective masks, speed and export fidelity with blinded judgments. The mocked 192 tests are NOT proof of professional image quality.

## No-install-on-laptop cloud build

Push this project's root contents to a repository with GitHub Actions enabled. Open the **Actions** tab, choose **Build and verify debug APK**, click **Run workflow**, and review the logs and `FilterSlap-V4-debug-CANDIDATE-not-device-tested` artifact. If test failures persist, the workflow job is deliberately red even if a candidate APK artifact is available. Note that the workflow itself has **not been run** here. No desktop Android Studio installation is required for GitHub's hosted builder.