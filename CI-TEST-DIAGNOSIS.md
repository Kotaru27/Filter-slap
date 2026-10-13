# CI unit-test failure investigation — 2026-10-13

## Actual GitHub Actions result

- Commit `a122c53471df06cd8a4b213bd099ced00587551a`; run `38421009267`
- Android Kotlin source **compiled** and the APK was **assembled, signed and 16-KB aligned**.
- `testDebugUnitTest`: **199 executed, 4 failed**. The CI release gate intentionally failed.
- Debug APK available as a **candidate only** in the run's artifacts. No on-device or real-photo validation.

## Diagnosis

All four failed cases were old `ImageForensicsTest` fixtures consisting of one single uniform RGB color. Their assertions assumed the pixels definitely represented a gray calibration card under colored light, even though the fixture supplied no metadata or reference establishing that. A colored surface under white light and a neutral surface under colored light are observationally indistinguishable in a uniform RGB frame.

The V4 white-balance engine intentionally refuses to force a global color shift when the frame has a single chromaticity. This behavior is protected by separate `ChromaticAmbiguityTest` cases covering multiple color axes and brightness modulation. Suppressing the V4 safety check, or exempting the four known RGB triplets, would degrade arbitrary photographs.

## Focused correction

Replaced the four **invalid premises**, not the safety rule. The regression fixtures now consist of a candidate-neutral region plus repeated, spatially balanced, independent colored regions. They explicitly assert that the analyzer finds chromatic diversity, anchor coverage, and no conflicting split lighting *before* expecting white-balance/tint decisions. The green-tint case additionally checks that the renderer shrinks its green-channel bias.

The original monochromatic abstention tests are retained unchanged. These new fixtures test a heuristic on broader evidence, **not** ground-truth identification of neutrals (which generally requires a trusted reference). No image-specific production thresholds, pixel exceptions, subject-coordinate rules, hardcoded test-photo values, or bypasses of failed tests have been introduced.

Host-only verification: `ImageForensicsTest` 21/21 in the mocked Kotlin runner after the change. This is not Android/Gradle verification. Check the subsequent Actions run for authoritative results.

## Remaining gates

1. GitHub Actions `testDebugUnitTest` must pass in the real Android build environment.
2. APK assembly, signing, alignment checks, and artifact upload must pass.
3. Device installation and picker → edit → compare → export → history must be verified.
4. Real photographic image quality must be evaluated on independent, varied photos without encoding test photos into production logic.