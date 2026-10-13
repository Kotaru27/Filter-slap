# V7 verification record

V7 is an offline selective-enhancement beta. This record separates execution evidence from photographic preference and physical-device acceptance.

## Completed locally

- Full Android JVM suite: **222 tests, 0 failures, 0 errors, 0 skipped**. This includes caller-bitmap ownership, cancellation propagation, gamut direction, clipping transitions, black/white preservation, recipe consistency and private photo audits.
- Existing Python research suite: **14 tests passed**. Its generated synthetic voting fixtures are test data, not human preference evidence.
- Pinned V6 weights verified against Git blob SHA-1 and SHA-256; ONNX classifier parity passed three independently seeded PyTorch probes. Foreground ONNX verified against exact size, SHA-256 and upstream MD5, with two finite-output probes.
- Supplied 864 × 1536 photo run through the actual Kotlin engine using Robolectric native graphics and desktop ONNX native runtime. Both bundled models ran; a salient-object mask was accepted. The original bitmap survived analysis.
- The sample's V7 pre-encode mean RGB change was approximately **6.23 / 255**, with newly clipped channels at about **0.0013% of pixels**, newly crushed luma at about **0.0027%**, and no samples flagged for the specified large hue-rotation check. These are technical damage metrics, not an aesthetic score.
- Re-rendering the same sample recipe for export produced identical pixels at the same dimensions.
- Twelve public upstream U-2-Net reference photos were processed with the same engine. All final renders passed the configured damage checks and same-recipe repeat-render equality. Eleven received an edit; one retained the original. These are reference/demo images, **not a proven held-out aesthetic benchmark**.
- Debug application and Android instrumented-test APK compiled. Application signature verification and 16 KB ZIP alignment passed for the locally built universal candidate.

Private user photos and private audit outputs are excluded from the repository. Model and recipe fingerprints appear in diagnostics. The final APK's source revision is embedded in `BuildConfig.SOURCE_REVISION` and the Edit Report.

## Android instrumentation

`OfflineRuntimeTest` exercises the actual Android ONNX library, Bitmap decode/render, same-recipe export, the application's gallery-save function, and reopening the saved JPEG. It requires a private input copied into the debug target's `files/audit.jpg`. A local software-emulator execution is recorded separately from physical-device tests.

This document is updated with the instrumentation result before delivery. No emulator result should be interpreted as proof of latency, memory or visual quality on the user's phone.

## Remaining release qualification

- Installation, photo picker, repeated crop/compare/save interactions and memory/latency measurements on physical phones, including the intended Android 16 device and appropriate minSdk/16 KB configurations.
- Broad color-profile/HDR handling tests. V7 requests an SDR sRGB decode on API 26+; that request alone does not prove every import path is correct.
- A 200-photo, genuinely held-out and independently blinded preference assessment. The proposed >65% improvement preference and >95% already-good non-damage rates remain unmeasured.
- Foreground-mask confidence calibration on annotated unseen photographs. Current geometric validity and boundary refinement reduce some risks but cannot prove object-mask correctness.

Do not describe this beta as universally professional, physically device-tested or aesthetically superior across all photographs based on the checks above.
