# Filter Slap — Learned Photo Engine (research, not an Android update)

**Status:** two trainable, offline-compatible enhancement prototypes implemented. Both start with an *identity* transform and render full-resolution original RGB data through learned color/tone coefficients. They are **not yet trained or validated on an authorized professional retouching dataset**, and no photographic-quality improvement has been demonstrated. Android V9, V9.3, and `main` are unchanged.

## What is actually implemented

- `model.py`: learned **global** or **spatially adaptive** 3×4 RGB affine coefficient grids, guided by the original pixel luminance; whole-image low-resolution CNN context, zero-initialized correction head, bounded output, global image coordinates to avoid tile seams. No test photo names, hardcoded objects or per-scene image fingerprints.
- `data.py`: paired images with matching geometry, EXIF/ICC-aware sRGB conversion, split and capture-group leakage checking, explicit self-declared rights, and optional 'preserve' labels for already-good images.
- `train.py`: reproducible train/validation-only optimization and validation-based checkpoint selection. No test-set tuning.
- `evaluate.py`: full-resolution tiled rendering, diagnostic distances to expert edits, blinded side-by-side comparisons against **actual exported V9/V9.3 APK outputs**, and independent human vote aggregation. Neither L1 nor synthetic checks can approve release.
- `export_onnx.py`: optional ONNX export of the predictor ONLY, with PyTorch-vs-ONNX Runtime parity probes. It is **not** an Android app integration or a completed mobile renderer.
- `tests/`: isolated synthetic invariant tests; intentionally **no claim of expert photographic improvement**.

## Dataset needed to achieve the goal

Use only legally usable, genuinely co-registered, **expert-retouched source/target pairs**. Provide the private manifest defined in `manifest.example.csv`. Both sides of each pair must be developed into the same sRGB space. If using MIT-Adobe FiveK, review image-by-image rights and convert RAW DNG + expert ProPhoto TIFF correctly; never pair an undeveloped RAW image directly with an expert TIFF or silently treat ProPhoto as sRGB. PPR10K photographs have **non-commercial restrictions** and cannot be assumed valid for commercial training.

The manifest's `rights` entry is a declaration, **not a verified license**; separately confirm contracts and redistribute/training rights. Keep personal/private images and learned weights out of public Git history.

Use disjoint capture/session groups in `train`, `val`, and `test`. The default trainer requires at least 50 training pairs, 20 validation pairs and 20 held-out test pairs; these are bare **pipeline minimums**, not meaningful professional-quality evidence. Aim for hundreds/thousands of diverse training pairs and 200–300+ genuinely unseen test images including skin tones, skies, neutral products, mixed light, low light, dark materials, already-good images, and intentional artistic shadows.

## Commands

```bash
python -m pip install -r research/learned_photo/requirements.txt
python -m unittest discover -s research/learned_photo/tests -v

python research/learned_photo/train.py --manifest /private/pairs.csv --out /private/global --mode global --epochs 40
python research/learned_photo/train.py --manifest /private/pairs.csv --out /private/spatial --mode spatial --epochs 40

python research/learned_photo/evaluate.py render --manifest /private/pairs.csv --checkpoint /private/global/best.pt --out /private/global-val --split val
python research/learned_photo/evaluate.py render --manifest /private/pairs.csv --checkpoint /private/spatial/best.pt --out /private/spatial-val --split val

# After freezing the winning approach using validation (NEVER using test for selection):
python research/learned_photo/evaluate.py render --manifest /private/pairs.csv --checkpoint /private/spatial/best.pt --out /private/spatial-test --split test
python research/learned_photo/evaluate.py blind --manifest /private/pairs.csv --challenger /private/spatial-test --baseline /private/actual-v9-exports --cards /private/cards --key /private/secret-key.json
# Fill /private/votes.csv independently, without exposing secret-key.json.
python research/learned_photo/evaluate.py score --key /private/secret-key.json --votes /private/votes.csv --out /private/score.json

# Optional model-only ONNX conversion (requires onnx, onnxruntime):
python research/learned_photo/export_onnx.py --checkpoint /private/spatial/best.pt --out /private/onnx
```

## The NON-NEGOTIABLE release gate

The objective is **photographic quality, not faster editing**. Compare real originals, model output, professional reference and actual V9/V9.3 exported images. Have independent reviewers judge natural color, highlight detail, shadow intention, material texture, depth, subject prominence and overall professional finish. Prefer a pre-registered target of >=70% blinded preference over the strongest baseline on an independently held-out diverse cohort and very low serious regressions, with category breakdown and statistical uncertainty.

**Do not enable the research model in Android until it clearly passes the human visual comparison.** Even then, require full-resolution preview/export equivalence, ONNX/Android pixel parity, licensed redistributable assets, first-run airplane-mode testing, cancellation and memory reliability. No new APK is claimed to be better until these gates pass.

### Current evidence

Local mathematical training/render tests and GitHub Actions tests are **synthetic**. No professionally retouched licensed corpus or independently reviewed A/B evaluation is available in this repository. A passing CI job is therefore NOT a measured photographic improvement. The research branch deliberately does not change `app/`.

Further photo license checks: [MIT-Adobe FiveK](https://data.csail.mit.edu/graphics/fivek/) and [PPR10K](https://github.com/csjliang/PPR10K).
