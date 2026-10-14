# Independent V9 audit — measurement first, no renderer modifications

Baseline: `77004f7b55f7ceb14e42b56e37d27198fb755338` (`1.5.0-v9-beta`).
Audit branch: `audit/v9-independent`. The renderer, model-selection logic, app UI, and export source are identical to V9. Changes on this branch are only this audit, a **measurement-only** Robolectric test, and CI artifact uploading.

## Verified directly from V9 source

1. `OfflineEnhancer.prepare` proposes V8 tone options plus V9 spatial options, selects them from a 384-pixel preview, and `render` reuses the chosen recipe on output. Its output-damage checker tests mean RGB change, clipped/crushed pixels, hue rotation and excess vividness.
2. `CandidatePlanner` permits spatial light-recovery proposals when `intentionallyDark` is true if the texture heuristic identifies `spatialPotential > 0.025`. Its score also rewards shadow-brightness recovery independent of dark-scene intent.
3. Every non-identity candidate automatically receives noise-conditioned detail enhancement and denoising, whether or not either independently improves the source. This can change candidate rankings and local texture.
4. Pixel damage metrics do not fully capture uncontrolled *desaturation*, color appearance, perceptual texture or photographic taste.
5. Model selection and preview rendering use up to 384-pixel short-side-independent previews. Spatial lighting is recomputed at a 320-pixel bound for final output, and neighborhood-detail radius depends on image dimensions; approximate preview/export consistency is **not** equivalent to pixel equality at different sizes.
6. Import preview is bounded to 2048 pixels on the long side. Export is constrained by a 4096-pixel long-side limit and a heap-derived pixel budget, rather than promising full camera resolution.
7. The optional real-photo audits in `PrivatePhotoAuditTest.kt` call `assumeTrue` and **skip** when `FILTER_SLAP_AUDIT_DIRECTORY` or `FILTER_SLAP_AUDIT_INPUT` is absent. Routine green CI does not establish real-photo improvement.

## V9 versus V10/V11 regression mechanism (source confirmed; effect magnitude not benchmarked)

V10 added a linear-sRGB tone-only chroma operation to `EnhancementMath.edit` after the V9 tone adjustment. For tone-only candidates, this changes V9 pixel output, can compress the source chroma as luminance moves toward gamut boundaries, and was not part of the V9 rendering pipeline. V11 retained this operation and only changed candidate quality penalties and related guards.

This is a **credible** regression mechanism, not proof that every V10 or V11 image is aesthetically worse. Only paired inputs processed with the same exact revisions can establish the actual outcome.

## Measurement harness (synthetic, not photographic preference)

`V9IndependentBaselineAuditTest.kt` renders seven existing algorithmic source fixtures: balanced, low key, sky, vegetation, skin tone, warm/cool split, fine detail. It records the chosen recipe and numeric source/output measurements and verifies same-resolution recipe replay and pixel-safety checks, without writing images.

CI artifact: `V9-synthetic-baseline-metrics-NOT-PHOTO-QUALITY`.

**Do not** use any of these synthetic fixtures, filenames, test hues, thresholds or recipe outcomes to create production image-specific branches. They exercise invariants only.

## What must happen before quality-changing code can be promoted

- Acquire a separate, licensed and diverse group of **untouched originals**, processed offline under identical conditions by V9 and any new candidate. Do not commit private test photos.
- For each paired output, independently review subject separation, natural color and white balance, sky and skin appearance, highlight/shadow detail, halos, denoising artifacts, and overall photographic preference. Include blind side-by-side comparisons.
- Record original-vs-edit mean changes, hue/chroma changes, clipping, highlight variance, texture retention, and preview/export agreement. These metrics are diagnostics, **not substitutes** for expert visual review.
- Assess cold and warm processing time, memory peak, cancellation and repeated-image stability, and airplane-mode behavior on representative real Android devices.
- Keep V9 unchanged for A/B comparisons until a candidate demonstrates no material degradation and clear improvement across unseen scenes. Preserve `main` and older branches; build success alone is not an approval gate.

**Conclusion:** V9 is a useful candidate comparator with real limitations. Its professional-quality status is *not verified*. No renderer fixes should be merged on the strength of this report alone.
