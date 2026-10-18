# Filter Slap V12 — classical creative grading (NO AI)

**Status:** experimental engineering branch, not a photographic quality release.
Base: `upgrade/v9-7-real-manual-photo-editor` (retains the manual editor, undo/redo, crop, compare, offline gallery export).

## Why this exists

The former automatic candidate planner explicitly optimized **defect removal minus pixel change**. A safe local light recovery could win even when a deliberately graded photograph would be more compelling. The old automatic result also wasn't the default image shown on the V9.7 manual-editor branch.

## New pipeline

1. Analyze robust original tone quantiles and full-frame color distributions. No scene classifier, image ID, coordinate-specific preset, cloud access, pretrained model, AI inference, or per-test-image constants.
2. Evaluate the old correction candidates **plus** three continuous image-derived variants of contrast shaping, protected shadow depth, bright-end shoulder, saturation-dependent vibrance, and subtle luminance-dependent shadow/highlight color separation.
3. Render actual candidates at common preview resolution. Every candidate still passes the existing true rendered-pixel clipping, crushing, color integrity, hue drift and local artifact checks.
4. The classical creative benefit function can reward **only source-evidenced** contrast separation and high-light control; it penalizes excessive median drift or collapsed tonal range. Pixel change by itself never earns a positive score.
5. Retain the selected immutable creative grade in the full-resolution `EditRecipe`, use the same recipe for gallery export, and scale down the entire recipe when final-pixel validation fails. Fallback can keep the original.
6. The UI defaults to the selected automatic result, but the user can switch the manual editing base to the original. The report lists `CreativeTone` and `CreativeColor` only when the selected grade actually applies.

**Important:** This is a modest first implementation in *display-encoded sRGB*, not a full linear-scene-referred RAW pipeline or a copy of darktable. JPEG highlight detail that is already clipped in the source **cannot be restored**. The current bounded gamut mapping and damage rules may reject artistically reasonable transformations. Additional research and genuinely independent photographic evaluation are required.

## Independent open-source references

- darktable sigmoid and filmic modules: scene exposure and midtones should be treated separately from final display tone mapping; smooth shoulder, toe and controlled color preservation are important. Documentation: https://darktable-org.github.io/dtdocs/en/module-reference/processing-modules/sigmoid/ and https://darktable-org.github.io/dtdocs/en/module-reference/processing-modules/filmic-rgb/
- Source implementations for architectural study: https://github.com/darktable-org/darktable/blob/master/src/iop/sigmoid.c . Its GPL source was **not copied** into Filter Slap.

## Tests and release gating

- `CreativeGradingTest`: scene-dependent parameter change, graceful neutral identity, bright-tone shaping, negative scoring of degraded structure, and attenuation.
- Existing UI/export, candidate, color, manual editor and processing regression tests must pass.
- `RealPhotoComparisonTest` renders six CC0/public-domain photographic fixtures through the **real** automatic engine and produces original/edited pairs and CSV metrics, for *human* review.
- CI fails closed on unit-test failures; it checks the version and APK signing, native alignment, and absence of model files/inference runtimes.
- Do not promote this branch to `main` or declare "professionally edited" until diverse blinded original/edited preferences and physical-phone preview/export parity are verified. A higher mean RGB difference is not proof of improvement.

## Known limits

This patch does not claim semantic subject-aware masks, lens-perspective correction, wide-gamut/HDR camera-pipeline parity, superior face/skin handling, improved noise processing, or a measured artistic preference win. The existing manual editor is unchanged. All photographic parameters are computed generically, not tuned to submitted user photos.
