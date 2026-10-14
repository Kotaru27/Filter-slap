# Filter Slap V8 candidate planner — qualification in progress

## What actually changed

- The old V7 Android UI and offline pretrained inference remain. V8 adds a scene-evidence-driven *candidate* pipeline rather than just multiplying one image enhancement by an arbitrary strength.
- Competing plans can use a stronger learned global grade, genuine exposure correction even without the model, foreground exposure, independently controlled background exposure, bounded contrast, and cautiously gated neutral-anchor white balance.
- Candidates are rendered on the same modest preview and judged for **measured tonal-defect reduction versus the original**. Changes in RGB values do not earn points merely for being large. No image IDs, object labels, per-photo colors, coordinates or example-image parameters are used.
- The original photograph competes as the identity candidate; safety rejects candidates that introduce clipping, crushing or extreme chromatic shifts. The selected recipe is reused for saved full-resolution output.
- The Edit Report exposes scored candidate alternatives and the selected strategy. Model weights and source photograph remain offline.

## What is NOT demonstrated

- Automatic candidate scores are objective *technical correction proxies*, not a learned photographic taste model or proof of aesthetic superiority.
- An already-balanced photograph can correctly remain unchanged. Stronger corrections require evidence, but a missing scene-understanding signal may cause a false abstention.
- Full phone performance, export and diverse photo quality require real device checks and blinded tests; automated unit tests and a successfully compiled APK cannot establish these.
- The existing saliency foreground is not semantic object recognition. V5 renderer is kept in source but the V8 path implements selected tonal controls independently rather than copying all legacy effects.

## Next gates

1. Full Android unit tests including candidate-planner counterexamples, signed APK build and model-asset checks.
2. Measure strategy selection, candidate scores, subject and background curves, out-of-gamut artifacts and latency on a varied, lawfully usable unseen image set.
3. Blind preference study of original, V7 and V8, with no test-specific branches added to source.
