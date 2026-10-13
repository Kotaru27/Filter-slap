# Filter Slap V6 — implementation record

**Milestone:** Gate A (model benchmark foundation), *not* V6 Android release.

- Implemented paired Image-Adaptive 3D LUT architecture reconstruction, NumPy 3D LUT renderer, model-download pin+verification and synthetic smoke probe.
- Implemented CSV manifest validation, real-V5-output pairing, protected split groups, rights field, no user-photo uploads, deterministic diagnostic metrics, randomized blinded A/B cards, private key separation, voting aggregation and optional no-edit control.
- Added isolated CI workflow for research model retrieval/inference and regression suite.
- Actual model weights have not been packaged with the Android app; existing V5 is not modified.
- CI trained-weight smoke evidence and photographic quality results MUST be filled only after observing them. A synthetic check is not evidence of actual photographic preference.
- Remaining work: run pinned-weight smoke, collect lawful 200+ held-out photos, export matching actual V5 images, blind-evaluate model, judge acceptance targets, decide on subject-masking alternative and Android inference conversion. The current environment does not have user-authorized benchmark photos and should never fabricate quality claims.

Full instructions: `research/v6/README.md`.

## Cloud verification observed on first V6 research commit

- GitHub Actions run: https://github.com/Kotaru27/Filter-slap/actions/runs/38434151255 — **success** for first V6 research milestone.
- 12 deterministic tests passed in CI; first verified model download, paired-classifier load, and numerical inference smoke passed on three synthetic inputs.
- Pretrained sRGB Git blob pins matched upstream; additionally captured exact SHA-256: LUTs `c1bb2bc4b7239c1a7e96159f5923123ba796b1fceb0b8c3132b423ea825b821a`, classifier `bae9865395625ecae58cfe86147e521093bb1e29e7b2544e02adb238b8035021`. SHA-256 validation is now added to the downloader too.
- Coefficient maximum separation was `3.69645` across three synthetic inputs, demonstrating *different* learned global transformations (NOT superior quality).
- **Warning observed:** synthetic RGB color-noise checker induced about `16.57%` out-of-range output channels before clipping. This demonstrates a possible failure mode; the input is not a normal photograph, and this is NOT a real-photo defect rate. Do not ignore clipping risk in held-out evaluation.
- After ICC conversion and SHA-256 pinning, local research unit tests pass **14/14**. CI should re-run on this change; this paragraph does not claim the amended code has already passed cloud CI.
- A standard V5 Android build may also be triggered by GitHub when commits reach `main`; such an APK is **still V5**, not a V6 model-integrated Android build.
