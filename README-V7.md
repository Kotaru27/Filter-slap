# Filter Slap V7 — offline selective enhancement beta

V7 makes automatic enhancement eligible independently of V5's defect flags. It uses the existing checksum-pinned paired-sRGB adaptive LUT and a bundled 4.6 MB U2NETP salient-object model. The Android application has no Internet permission and makes no inference or model-download requests.

## Implemented changes

- The foreground model distinguishes a salient subject from the background, including general objects. Raw output is retained; flat/invalid/very broad masks are rejected instead of being normalized into a confident mask. These validity checks are not proof that every mask is correct.
- Adaptive strength trials protect highlights, vivid colors and true black. RGB deltas are reduced with one common gamut scale; channel clipping is not used to manufacture a vivid edit.
- A continuous subject shadow lift preserves bright reflections and deep engraving. Detail is restrained at strong edges, in the background and when native-resolution noise estimates are elevated. Gentle luma noise suppression is limited to smooth regions.
- Every rendered pixel is checked for newly clipped/crushed samples, large hue/saturation shifts and excessive mean change. Recovery elsewhere cannot hide a newly damaged region.
- The preview prepares an immutable recipe. Save uses that exact recipe and reports a safety failure instead of silently choosing another grade.
- Small-image analysis no longer recycles caller-owned input. Request identifiers prevent obsolete photo/crop work from publishing, and cancellation is propagated through analysis and render rows. Native inference checks cancellation before and after each model call.
- Decode requests a software sRGB SDR contract on supported Android versions. Export retains the existing hardware-safe resolution and memory limits and shows actual saved dimensions.
- The Edit Report shows the actual outcome, recipe, build revision and diagnostic reasons. An unavailable model is not labelled as proof the source was already perfect.

## Build

Prepare the pinned V6 assets as in the existing CI workflow, then run:

```sh
python research/v7/prepare_assets.py --output-dir app/src/main/assets
./gradlew testDebugUnitTest assembleDebug
```

Assets are generated at trusted build time. Source excludes checkpoint binaries, personal photographs and APKs. `research/v7/prepare_assets.py` verifies the foreground ONNX file against SHA-256, upstream MD5 and its exact size, then executes two numerical validity probes.

U2NETP reference: Xuebin Qin et al., [U-2-Net](https://github.com/xuebinqin/U-2-Net), Apache-2.0. ONNX conversion: [rembg v0.0.0](https://github.com/danielgatis/rembg/releases/tag/v0.0.0). SHA-256: `309c8469258dda742793dce0ebea8e6dd393174f89934733ecc8b14c76f4ddd8`. Licenses and model credits are included in the APK assets.

## Verification boundaries

Build outputs include ARM64, ARMv7, x86, x86_64 and a universal APK. CI publishes the smaller ARM64 variant for contemporary Android phones. The emulator audit uses the x86_64 variant of the same source and model assets.

Read [VALIDATION-V7.md](VALIDATION-V7.md) for actual results. Synthetic tests, checksum verification and compilation do not establish professional aesthetic quality. This is a beta candidate; masks can be wrong, native inference is not interrupted mid-call, the global grade is not a generative editor, and unrecoverable JPEG highlights cannot be recreated. A 200-photo blinded review and physical-device matrix remain separate release gates.
