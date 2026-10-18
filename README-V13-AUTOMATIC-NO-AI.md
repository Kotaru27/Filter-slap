# Filter Slap V13 — Automatic photographic editing, NO AI

**EXPERIMENTAL, not a professionally validated release.**
V13 replaces the incorrect manual-first direction of V12 with a one-tap classical automatic editor. Neither a manual slider tray nor its renderer is shipped in this branch. Original comparison, reporting, crop selection, history and full-resolution gallery export are retained.

## Implemented automatic decisions

1. Robust whole-frame tone quantiles, chroma statistics and scene-light measurements drive continuous grading parameters. No image labels, faces, sample IDs, locations, fixed photo-specific recipes, models, or network dependency.
2. Actual photographic controls: midtone recovery for dim non-low-key scenes; source-derived contrast shaping; shadow-depth toe; highlight shoulder; chroma-adaptive vibrance; subtle luminance-dependent split toning.
3. Monochrome detection entirely from input colors, with zero creative chroma and zero split-toning on achromatic sources.
4. Candidate alternatives: restrained natural, dimensional, highlight-balanced, plus a dimensional grade combined with edge-aware shadow recovery when real evidence exists.
5. Every candidate is rendered and evaluated. Added quality terms compare **actual output** tonal separation, meaningful shadow depth, highlight control, dynamic-range flattening, midtone drift, and source-conditioned color gain/loss. The existing candidate correction scores and pixel-damage gates still apply.
6. The original image remains an eligible candidate. A strong but damaged edit must be rejected, not declared an improvement because it changed more pixels.
7. The winning immutable `EditRecipe` is used for automatic preview and full-resolution saved output, including strength attenuation on failed final-pixel safety checks.

## Development and validation

- Kotlin regression tests check photo-dependent decisions, dark-scene preservation, saturation penalty, monochrome preservation, and deterministic preview/export.
- CI runs a six-photo public-domain/CC0 comparison and saves side-by-side PNGs plus detailed metrics. **These are evaluation inputs only; there is no special case for them in production code.**
- CI rejects failures and verifies APK identity, signature, zip alignment, and absence of model assets or AI runtimes.
- Human comparison is required for artifact, skin tone, hue, light, contrast and source intent. A higher pixel delta is not proof of photographic quality.

## Important limits

The approach is still classic display-encoded sRGB processing, not a RAW/scene-referred photographic pipeline. Local structure relies on tonal evidence, not semantic scene understanding. Already-clipped highlights cannot be reconstructed. No quality claim should be made from synthetic regression tests or just six photographic examples.

**Do not merge as a quality release before blinded testing on a varied independent set, including real-device preview/export comparison.** Avoid future manual-only or UI-only iterations when evaluating the user's request for automatic quality.

## Technical research references

- darktable source reference for photographic tone mapping concepts (not copied): https://github.com/darktable-org/darktable
- darktable sigmoid and filmic RGB photographic pipeline docs: https://darktable-org.github.io/dtdocs/en/module-reference/processing-modules/sigmoid/ and https://darktable-org.github.io/dtdocs/en/module-reference/processing-modules/filmic-rgb/
