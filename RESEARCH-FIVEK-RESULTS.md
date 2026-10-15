# First real expert-retouch training results — 2026-10-15

**Research only. NOT an Android upgrade, trained-weight distribution, commercial-license clearance, or verified professional visual quality.**

## Training provenance

- Dataset: [MIT–Adobe FiveK](https://data.csail.mit.edu/graphics/fivek/), official RAW DNG images paired with original Expert C TIFF retouches.
- Original RAWs converted to sRGB with rawpy using camera WB/no automatic brightness. Expert TIFFs converted from embedded ICC profile to sRGB.
- Dataset split metadata: the [FiveK metadata mirror](https://huggingface.co/datasets/yuukicammy/MIT-Adobe-FiveK) with deterministic, source-content-independent selection. Dramatic crop/aspect mismatches and oversized assets excluded; no per-image retouching rules.
- Model candidates: the existing learned global RGB coefficient-grid editor and spatially adaptive editor; identity initialized, optimized against expert pairs.
- **64** training, **16** validation, **16** strictly held-out test photographs. Training: 6 epochs; selection using validation objective only. All images/data and learned weights remained on the ephemeral GitHub Actions runner and were not placed in the public repository or artifact.
- Tested research code is the `research/fivek-real-pilot` branch at `f41c8eeead95bfbab41a1c9df7f1b32c57927438`.

## Real dataset results (normalized RGB MAE to Expert C)

| Metric | Value |
|:---|---:|
| Global model validation objective | 0.112325 |
| Spatial model validation objective | 0.113408 |
| Selected by validation | **Global** |
| Validation unedited → expert L1 | 0.164893 |
| Validation global → expert L1 | 0.106170 |
| Test unedited → expert L1 | 0.126556 |
| **Test global → expert L1** | **0.079794** |
| Held-out test relative L1 reduction | **36.95%** |

Source of truth: [successful GitHub Actions run #38743661508](https://github.com/Kotaru27/Filter-slap/actions/runs/38743661508), artifact `real-FiveK-expert-96-pair-pilot-METRICS-ONLY-NOT-RELEASE`.

An earlier **12/4/4** training/validation/test research pilot also succeeded at [run #38742616938](https://github.com/Kotaru27/Filter-slap/actions/runs/38742616938), selecting spatial on four test images with **6.90%** relative RGB error reduction. These two test cohorts differ, so the percentages are **not** a controlled head-to-head measure of the benefit of more training pairs.

## Identical-photo actual V9 source-code benchmark (completed)

A second full research run re-trained and selected the global editor using validation only, then built the pinned V9 color and foreground assets and ran the **unchanged V9 Android `OfflineEnhancer`** within Robolectric on exactly the same 16 held-out expert-paired photographs. The research-only test class was copied into the CI runner and **no Android source file was committed**.

| Held-out test metric | Value |
|:---|---:|
| Unedited → Expert C normalized RGB L1 | 0.126556 |
| Actual V9 renderer → Expert C L1 | 0.108350 |
| **Selected learned global → Expert C L1** | **0.079794** |
| Relative learned error decrease vs V9 | **26.35%** |
| Learned closer to Expert C than V9 | **14 / 16** |
| V9 closer to Expert C than learned | **2 / 16** |
| Mean paired L1 margin (V9 - learned) | 0.028556 |
| Bootstrap 95% CI of paired mean margin | **0.001106 – 0.053213** |

[Successful source-of-truth GitHub Actions run #38745457853](https://github.com/Kotaru27/Filter-slap/actions/runs/38745457853), aggregate-only artifact `real-FiveK-expert-96-pair-pilot-METRICS-ONLY-NOT-RELEASE`. The V9 algorithm was executed using Robolectric and pinned offline model assets; **this was not a physical-device APK test**.

This is an evidence-based improvement in **pixel agreement with Expert C**, not proof of professional appearance. Error reduction has nontrivial uncertainty with only 16 photos. L1 does not establish human preference, natural skin/material color, nuanced light, or absence of image-specific damage.

## Absolutely not yet established

- **No human photographic preference** and no artist/retoucher review. RGB proximity to one expert is an imperfect target and may favor an unwanted color or tone style.
- **No physical-device V9/V9.3 APK export comparison.** The unchanged V9 Android editing code has now been compared on the same photos under Robolectric, but no real-device round trip.
- **Sixteen** test photos is too few for a strong generalization or safety claim. Error averaged over pixels can hide some bad individual outputs.
- No professional-style subjective assessment of portraits, night scenes, dark materials, deliberate shadows, textured architecture, skies, or skin-tone fidelity.
- No mobile runtime inference or photo output parity.
- Dataset is research-licensed; commercial use of images and downstream distribution of learned weights **is not cleared**. Research weights were intentionally not redistributed.

## Specific next gate

1. Run unchanged V9/V9.3 on the exact source photographs and aggregate the same expert-reference metrics.
2. Acquire a larger legally suitable, expert-paired dataset with evaluation-only cases kept outside training and validation. Fix the trained approach using validation data.
3. Review blinded original / V9 / learned output / expert-reference comparisons across hundreds of diverse test photographs; include per-case catastrophic-failure rates and confidence intervals.
4. Only after visual preference and model-license clearance, implement model inference inside the offline Android app and verify full-resolution output and export consistency.

**Do not merge this research into Android main or call it a professional-quality app upgrade yet.**
