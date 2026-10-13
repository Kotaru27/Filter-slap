# Filter Slap V6 — implementation record

**Milestone:** Gate A (model benchmark foundation), *not* V6 Android release.

- Implemented paired Image-Adaptive 3D LUT architecture reconstruction, NumPy 3D LUT renderer, model-download pin+verification and synthetic smoke probe.
- Implemented CSV manifest validation, real-V5-output pairing, protected split groups, rights field, no user-photo uploads, deterministic diagnostic metrics, randomized blinded A/B cards, private key separation, voting aggregation and optional no-edit control.
- Added isolated CI workflow for research model retrieval/inference and regression suite.
- Actual model weights have not been packaged with the Android app; existing V5 is not modified.
- CI trained-weight smoke evidence and photographic quality results MUST be filled only after observing them. A synthetic check is not evidence of actual photographic preference.
- Remaining work: run pinned-weight smoke, collect lawful 200+ held-out photos, export matching actual V5 images, blind-evaluate model, judge acceptance targets, decide on subject-masking alternative and Android inference conversion. The current environment does not have user-authorized benchmark photos and should never fabricate quality claims.

Full instructions: `research/v6/README.md`.
