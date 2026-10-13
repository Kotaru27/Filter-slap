# Filter Slap V6 — model-evaluation track (NOT an Android V6 release)

**State: benchmark tooling implemented; photographic model NOT promoted.**

V5 remains the running Android photo editor. These Python scripts run on a computer or CI; no user photo is uploaded, no weights have been added to the Android app, and there is no V6 APK. This research track is designed to decide whether to replace V5's global color/tone decisions with an independently tested pretrained engine. It does not silently give the same LUT to every image.

## Upstream model and reproducibility

- Research reference: [HuiZeng/Image-Adaptive-3DLUT](https://github.com/HuiZeng/Image-Adaptive-3DLUT) ([Apache 2.0 source](https://github.com/HuiZeng/Image-Adaptive-3DLUT/blob/master/LICENSE)), **paired sRGB** variant.
- Exact upstream commit: `b491f6df64a588864739a157db271e5c848e1805`.
- Expected `LUTs.pth` Git blob SHA-1: `0830dceee2256ea5d0965c029945c329fac82a26`.
- Expected `classifier.pth` Git blob SHA-1: `439f2ada316f45d1d28dd86a161790aa3b4e334b`.
- Architecture: upstream paired `Classifier`, three pretrained 33³ RGB LUTs, content-dependent classifier coefficients. `adaptive_lut.py` uses Python/NumPy trilinear interpolation rather than the upstream native CUDA extension.
- Torch interpolation for classifier inputs matches upstream, **except** very large images (>5 MP) are thumbnail-downsampled to a max 2048 px first for CPU memory; this approximation must be parity-tested before adoption. For typical input sizes the full source tensor is given to the upstream-style 256×256 interpolation layer.
- Outputs are clipped to sRGB on export and the **pre-clip fraction** is reported. No global LUT can intelligently distinguish same-RGB pixels in different spatial regions; a subject-aware rendering component remains an independent future decision.
- Training dataset lineage, pretrained-weight permissions, and redistribution requirements require separate review before shipping the weights in a commercial app; a source-code license is not a blanket license for all photographs or third-party training data.
- Model weights are downloaded into ignored local `model_weights/`; **not** included in the APK or Git history.

## Quick start (offline local photos; network used only for weight download)

```bash
python -m pip install -r research/v6/requirements-model.txt
python research/v6/fetch_weights.py --output research/v6/model_weights
python -m unittest discover -s research/v6/tests -v
```

Download behavior: the downloader uses pinned commit URLs and checks Git-object SHA-1 against the exact upstream tree; wrong bytes fail closed. If model retrieval fails, **do not** substitute a random LUT, synthetic model, or weights from another source and call it pretrained.

### Create the benchmark manifest

Start from `research/v6/manifest.example.csv`. Save `manifest.csv` **outside Git**, with these columns:

`id,category,original,v5,rights,split,group`

- `original` = original sRGB image (with EXIF orientation corrected by reader).
- `v5` = **actual V5 Android-exported** image, not a fabricated Python simulation or re-rendering of the source. The comparison must hold output resolution consistent.
- `rights` = explicit permission to evaluate the photograph; no photos stored in the repository.
- `split` = `training`, `validation`, or `test`. Held-out `test` is never used to tune the model or code.
- `group` = capture/session ID; one group may not cross splits.
- `category` = scene category; include portrait, landscapes, low light, mixed light, architecture, already-good, and intentional artistic looks.

Do not put image paths with personal information in public logs. Never collect personal photos without permission.

### Validate, render and blind-review

```bash
python research/v6/benchmark.py validate --manifest /private/manifest.csv --require-v5 --min-test 200
python research/v6/benchmark.py render --manifest /private/manifest.csv --model-dir research/v6/model_weights --out /private/learned --split test --min-test 200
python research/v6/benchmark.py blind --manifest /private/manifest.csv --learned-dir /private/learned --out /private/cards-v5-v6 --key /private/secret-v5-v6.json --control v5 --min-test 200
# Copy votes_TEMPLATE.csv to votes.csv; fill reviewer ID, choice A/B/TIE and harm_side A/B/NONE
python research/v6/benchmark.py score --key /private/secret-v5-v6.json --votes /private/votes-v5-v6.csv --out /private/score-v5-v6.json

# Separately evaluate no-edit fidelity on the already-good subset using
# --control original and a distinct blinded key/output folder.
```

**Blindness controls:** reviewers receive only the original/reference plus randomized A/B cards. The mapping is saved in a separate secret key, never copied into the reviewer folder. Use at least three genuinely independent reviewers and multiple categories. Do not post the key or user photos to GitHub. Reviewers can still infer exact no-edit baselines; describe that limitation in findings.

**Pixel metrics** (clipping, average brightness, RGB chroma range, image change magnitude, source/model SHA256, inference latency) are descriptive **diagnostics, not beauty scores**. The tool does not announce a winner without blind reviews. Already-good images require a separate original/no-edit comparison. We do not substitute PSNR for preference or count a synthetic inference smoke as a real-photo result.

## Acceptance gates before Android model adoption

1. At least 200 lawfully evaluated, held-out, diverse real images; category/session split leakage checked; category distribution published in aggregate.
2. Blind comparisons demonstrate >=65% preference for learned V6 over actual V5 among decisive votes on images needing correction, with category breakdown and uncertainty. This is a **proposed target, not a measured win**.
3. Already-good photos preserved in >=95% of independently evaluated cases (not materially worse than no edit). Check portraits/skin tones and artistic intent separately. This is a **proposed target, not a measured fact**.
4. No systemic clipping, color cast, or artifacts across subject boundaries. Full-resolution quality and image geometry checked.
5. Real Android model conversion/parity, time/memory/thermal benchmarks on multiple devices, fully offline first-run model availability, license review, UI/export/lifecycle correctness.
6. Only after gates 1–5: implement Android model and subject-aware render path behind guarded feature flag; compare it against V5 in controlled rollout and build a V6 debug APK.

## Current evidence

- The NumPy 3D LUT interpolation and benchmark safety harness have **local deterministic tests**, not photo quality scores.
- GitHub Actions `V6 pretrained enhancement research checks` attempts to fetch the exact pretrained weights, loads the actual paired classifier and produces a **synthetic-only** coefficient-inference audit. Its success does NOT count as evaluation against real images.
- A local computer here cannot access upstream binary weight URLs because external DNS fails. The cloud workflow is the authority for real checkpoint inference.

## Licensing gotchas

- Do NOT import PPR10K photographs into commercial training or a public benchmark without permission: the dataset specifies *non-commercial research only*, despite the code repository carrying an Apache license.
- MIT-Adobe FiveK files have subset-specific licensing. Check each image's rights and preprocessing/color-space details before benchmarking; sRGB inputs and expert targets are not automatically interchangeable RAW/ProPhoto representations.

## Scope and non-regression

The existing V5 `app/` source and build configuration remain untouched in this research milestone. No learned algorithm is silently enabled on phones. If blind comparative evidence is negative or inconclusive, V5 stays as baseline and we reject/refine the candidate rather than force a version update.
