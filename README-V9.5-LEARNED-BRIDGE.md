# Filter Slap V9.5 — experimental offline learned-runtime BRIDGE ONLY

**Not a photographic quality release. NOT a trained-AI APK.**

This branch adds a native Android research bridge capable of loading an approved
offline learned-model predictor, rendering a candidate photo at original size,
and validating every pixel. **No learned predictor is bundled or enabled.**
The existing V9.3 pipeline is still the actual default editor, including
preview, editing strategy, crop, and export. The runtime version changes to
`1.5.5-v9.5-ai-runtime-no-model` to make this explicit; the Edit Report
correctly retains engine `V9_3_COHERENT_TONE`.

## Implementation

- `app/src/main/java/com/example/engine/learned/LearnedCoefficientGrid.kt`
  reproduces the PyTorch global/spatial coefficient grid shape
  `[1,12,8,H,W]` with `H=W=1` (global) or `16` (spatial), trilinear
  luminance+position sampling, source RGB affine coefficients and
  `source + 0.35*tanh(delta)` output. No per-pixel allocations or hardcoded
  photograph content.
- `LearnedOfflineCandidate.kt` prepares a normalized 256x256 full-photo
  predictor input, checks asset name and an externally supplied SHA-256 pin,
  runs ONNX Runtime using local asset bytes only, and renders row by row at
  original pixel dimensions. Alpha is retained. Full-pixel damage gate
  rejects candidates with new clipping, hue drift, or excessive changes.
- `OfflineEnhancer.previewApprovedLearnedModel(...)` is a **research-only
  opt-in** integration seam. It is NOT called by the production process()
  path. A missing approved pin always returns null. Unknown/unapproved
  assets are never auto-downloaded, loaded from gallery content or
  silently replaced by dummy model weights.
- Kotlin/Android tests cover zero-delta ARGB identity, deterministic
  affine output, spatial and luminance interpolation, nonfinite/invalid
  model tensors, fallback when model is missing, and candidate safety
  rejection. Existing V9.3 original/edited real-photo tests run unchanged
  and assert that the actual editing engine is still V9.3.

### Still UNVERIFIED

The Android predictor and native pixel renderer have not yet been compared
with an **actual rights-cleared trained ONNX checkpoint on the same
photographs**. Bitmap 256x256 scaling is not guaranteed to be bit-for-bit
equivalent to PyTorch's `F.interpolate(align_corners=False)`, and native
rounding/interpolation need separate ONNX/PyTorch/Android parity studies.
Full-resolution device reliability, performance and export-equivalence for
the learned model are likewise unverified.

## Independent human-quality review

The `research/quality/learned_blind_review.py` tool takes three directories:
unaltered originals, *actual* V9/V9.3 exports, and matching experimental
learned outputs. Every stem must match and all image dimensions must agree.
It creates randomized A/B comparison cards, a **separate secret key**, and
a review CSV. Reviewers see originals and two anonymous outputs, not the
secret mapping. Human score reporting requires independent reviewers and
includes serious-harm flags, per-photo majority, and a 95% Wilson interval.

```bash
python research/quality/learned_blind_review.py cards \
  --originals /private/source --baseline /private/v9-exports \
  --candidate /private/approved-ai-exports \
  --cards /private/review-cards --key /private/secret-identity.json

# After at least 3 independent reviews per photo:
python research/quality/learned_blind_review.py score \
  --key /private/secret-identity.json --votes /private/votes.csv \
  --out /private/preference-summary.json --min-cases 200 --min-reviewers 3
```

This tool cannot manufacture a quality improvement: real candidate images,
real baseline images, and human preferences are prerequisites. Synthetic
unit tests do not count as blinded photographic proof.

## Release blockers and conditions

1. Acquire expert-paired training material that explicitly permits the
   intended use, including on-device **distribution of learned model weights**.
   The prior FiveK weights were research-only and cannot be bundled.
2. Train/freeze models on lawful pairs and document provenance and
   distribution rights. Re-export a trusted ONNX model with a pinned hash.
3. Validate the ONNX predictor + native image shader against PyTorch with
   diverse unseen RGB inputs and actual full-resolution photos.
4. Run blinded comparisons against real, unchanged V9/V9.3 exports on a
   substantially larger independent image set. Track naturally dark
   objects, portraits, skin tones, highlights, sky, architecture, colored
   materials, low light, and already-good photographs. Reject repeated
   serious regressions; numerical L1 alone cannot approve release.
5. Only after these tests pass, explicitly wire a validated learned option
   into the UI, verify full-resolution crop and export parity, and run real
   hardware offline/airplane-mode tests.

**Until then, do NOT promote this branch as a better photo editor or
relabel its ordinary V9.3 edits as learned-AI output.**
