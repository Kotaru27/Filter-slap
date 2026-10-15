# Regression evidence audit — frozen protocol, 15 October 2026

This is a diagnostic iteration, not a learned-model quality upgrade. No
production Android code, model selection, strength list, or damage threshold
changes. Do not merge into main or publish an APK.

## Confirmed defects in the previous evidence

- Run 38770285292 saved only aggregate numbers. It did not retain which five
  images regressed, pixel hashes, model checkpoint hashes, or visual review.
- Its actual baseline is **V9_SPATIAL**, not V9.3. Inspecting the code shows
  that coherent tone and chromatic-integrity scoring are absent from the
  research branch. V9.3 must be measured separately, not relabeled.
- Inputs were capped at 384 pixels. Full-resolution behavior was unmeasured.
- The dataset uses `preserve=no` for every pair, disabling the preservation
  objective in this pilot. There is no already-good image evidence.
- Runtime source was fetched from a moving branch; Python requirements were
  ranges despite a step titled "pinned". An offset alone did not assert
  capture-level independence.

## Frozen comparisons

1. Recreate 64 train / 16 validation / 16 test, seed 27, test rank offset 64,
   384-pixel input, 6 epochs, original global/spatial validation selection.
2. Native learned source is pinned to
   `2229cb55108d400a86e188df10b84c0d0f44cd06` (PR #10).
3. Old Android V9 code is unchanged from research commit
   `1806937246c8066e8709ef36a9906159422bb3e9`.
4. V9.3 code is pinned to `03e587725058dcf31316e3fab19bff6c4677ffc2`.
   Every output must identify the expected engine in an execution assertion.
5. Fresh test: same seed, offset **128**, first **16** eligible captures,
   excluding all captures in the reproduction manifest. This experiment
   does not train again, choose a new model, or tune on either test cohort.
   Eligibility remains only original file-size and aspect-ratio checks.
6. Retain full source resolution for the first **four** eligible fresh
   captures, chosen before looking at pixels/scores. Measure native render
   and preview-versus-downsampled-full differences. This is a diagnostic,
   not real Android hardware testing or APK export validation.

## Reporting and interpretation

Publish per-capture numerical RGB/Lab/luminance/edge metrics, native strength
decisions, engine provenance and file hashes. Break out failures as well as
averages. Dark masks use source luminance; they do not infer black materials,
skin, authorial intent or segmentation. Gradient errors are texture proxies,
not proof of preserved detail. Lab delta-E76 is diagnostic, not preference.
An expert-informed best-strength curve is hindsight only; it must never feed
the app's selection. A safe strength is not necessarily a useful edit.

Private contact sheets contain original, actual V9, actual V9.3, native
guarded candidate and expert. Randomized blind-review materials are generated
separately, with keys outside the reviewer folder. No synthetic human votes.
Training weights never leave the experiment. For investigator access, visual
comparison sheets may be encrypted to the investigator's public key before
artifact transport; plaintext photos and checkpoints are excluded from public
artifacts. The decryption key is not in the repository or workflow logs.

No engine correction is preselected on the five regressions. The justified
improvements here repair evidence collection and baseline validity. A later
model change needs another fresh cohort, human review, lawful model rights,
and hardware/export tests.
