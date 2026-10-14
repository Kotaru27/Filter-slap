# Filter Slap V11: General photographic safety guards (beta)

## Why this candidate exists

V10 can approve perceptually worse results while passing synthetic safety checks. Successful APK builds do not prove professional photography. This patch does not claim to solve scene understanding or learned aesthetic quality.

## Scoped changes (no image-specific logic)

1. Respect `intentionallyDark` when proposing **local** lighting corrections, not just global/subject exposure.
2. Audit **lost bright-region texture**, including when average brightness and clipping stay unchanged. This extends the independent spatial cost applied to every candidate.
3. Penalize **severe saturation loss** from source to output for reasonably exposed chromatic pixels. Small desaturation and near-white gamut compression are exempt.
4. Add regression tests for all three failure modes. Existing whole-frame LUT, masks, cropping, offline rendering, safety/identity fallback, and high-quality JPEG export remain intact.
5. Set Android debug version `1.6.1-v11-beta`. Output engine label: `V11_GUARDRAILS`.

## Validation gates

- Source changes and regression tests must pass the GitHub Actions `Build and verify debug APK` job, including `testDebugUnitTest`, `assembleDebug`, APK signature check and 16 KiB alignment.
- **Not verified**: actual aesthetic improvement, neutral/skin fidelity across unseen photographs, physical-device latency/heat/memory, and complex HDR/wide-gamut capture; those require paired untouched originals and offline device checks.
- A screenshot of an edited image and report alone is not paired before/after evidence.
- Do not promote this branch to `main` or describe it as professional-quality until real-photo blind comparisons and device tests are reviewed.

## Next engineering stage

Build a diverse, consented benchmark with paired source-output crops and blinded human preferences; log candidate correction, highlight variance retention, chroma drift, color-appearance consistency, export/preview agreement and wall-clock performance. Move beyond fixed percentile rewards toward calibrated scene/context-aware quality models that are genuinely available offline.
