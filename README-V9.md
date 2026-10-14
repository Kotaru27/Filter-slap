# Filter Slap V9 — spatial photographic correction candidate

The previous V8.1 Android application remains preserved in Git history. V9 adds an **edge-aware local illumination map** built from each source photo using a guided filter and confidence in recoverable local shadow signal. This lets the editor propose shadow recovery in dark textured portions of an otherwise bright frame without routinely lowering the whole bright background. No object labels, colors, coordinates, scene IDs, or test images are hardcoded.

A separate source-defined spatial evaluator measures locally recoverable shadow lift, protectable bright-region drift, detail loss and contrast discontinuities for **every** editing strategy. The selector still includes the unchanged original and uses strict full-image output-safety checks. The existing offline learned LUT, saliency mask, gallery workflow and immutable preview/export recipe remain available. Under difficult scenes the engine may correctly select the original or an older photographic strategy.

**Important:** V9 beta's scoring remains a heuristic and must not be interpreted as professional photographic taste. Full-scale offline device testing, 200 unseen real-photo blind comparisons, accurate HDR/wide-gamut import/output and tiled full-resolution exports are not yet qualified. The application still limits export dimensions/quality according to the existing Android memory budget.

## Verification gates
- Pure Kotlin spatial mathematics compiled locally with synthetic shadow/bright/flat cases.
- Android CI must pass all required unit tests; source files/tests and offline assets must compile and package.
- Signed APK integrity, native alignment, and exported recipe consistency must be verified.
- Human preference improvement is **not yet proven**. Keep the V8.1 baseline for comparisons; no sample-specific production code.

Debug build `1.5.0-v9-beta` must be treated as an engineering/quality evaluation candidate only.
