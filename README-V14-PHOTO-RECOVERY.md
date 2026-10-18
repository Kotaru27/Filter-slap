# Filter Slap V14 — automatic offline camera-photo recovery (NO AI)

**Status: EXPERIMENTAL. Not a validated quality release.**

## User outcome

A photo taken by an average phone user should be automatically made **better**, not repeatedly given the same mild brightness/saturation adjustment. There are no editing sliders, learned models, cloud dependencies, category/image-specific hardcodes or studio-grade guarantees.

## What changed from V13

- An independent V14 development path now prepares one **image-derived photographic treatment**, instead of having the old correction-score competition regularly select the original/weak local recovery.
- Robust whole-photo luma histogram and source chroma drive each photo's exposure gamma, contrast, highlight shoulder, low-light recovery, color gain and restrained local clarity.
- **Illumination-aware local dodge/burn:** a guided (edge-aware) brightness field is rebuilt from the source image at preview and full-export resolution. Dark illumination regions are treated differently from brighter areas, with a soft boundary and near-black/highlight protection.
- **Low-key protection:** avoid lightening genuinely dim low-range photographs into daylight; this remains an inherently ambiguous inference without semantic scene understanding.
- **Measured white-balance correction** only for reliable neutral anchors that agree spatially; weak or contradictory measurements do not trigger a global correction.
- Monochrome source -> unchanged chroma. Vivid photo -> smaller chroma gain than a muted photo.
- The immutable `PhotoTreatment` follows the same full-resolution export path and scales with the existing final-pixel safety fallback. Existing clipping/hue/artifact quality gates remain.
- Automatic-only UI, comparison, crop, history and save workflow retained. No manual-editor reintroduction.

## Verification and photographic-quality gate

1. `AutoPhotographyTest`: per-photo parameter differences, low-key preservation, monochrome protection, safe zero-strength and source-dependent contrast.
2. `AutoRecoveryStressTest`: applies the **actual Android processing path** to public-domain/CC0 reference photographs after generic darkness, flat contrast or cool color casts. These transformations are *test-only*. Production never knows what photos or disturbances were used.
3. Existing `RealPhotoComparisonTest`: saves untouched originals alongside actual Android automatically edited photos; reports quantiles, selected recipe, clipping and color spread.
4. CI must pass unit tests, APK signing, alignment, version/no-AI binary checks. Pairs must be independently inspected. Compare V13 against V14 visually; **tests passing isn't proof of photographic quality**.
5. Compare source damage versus returned result on withheld photos; report improvement **and deterioration**, not pixel-change magnitude as success.
6. Do not merge into main or advertise a quality success without human-selected unseen photographs and preview/full-size-export verification on a physical Android device.

## Sources studied (architectural ideas only; no external source copied)

- darktable: histogram-driven exposure, filmic highlight roll-off and color-safe tone mapping (https://github.com/darktable-org/darktable and https://darktable-org.github.io/dtdocs/en/module-reference/processing-modules/exposure/).
- OpenCV image-enhancement concepts: luminance-local contrast, smooth spatial filters; indiscriminate global equalization can amplify noise and halos. No OpenCV or AI dependency added to Android. (https://docs.opencv.org/4.x/).

## Limitations

A classical system can adapt to lighting, texture and color measured in the image; it cannot reliably infer which dark scene was intentionally dark or what creative story was intended. Burned-out JPEG highlights cannot be recreated, poorly focused detail cannot be genuinely recovered, and unsafe changes may be attenuated. No claim of studio results or universal subjective superiority.
