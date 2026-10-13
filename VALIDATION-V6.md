# Filter Slap V6 build and photographic validation gate

Status: integrated **candidate**. Treat CI compilation and JVM tests as engineering evidence only. Do not treat them as human preference or installation proof.

## Cloud verification sequence
1. Pinned weights downloaded and verified against SHA-256 and Git blob SHA-1.
2. `torch.onnx.export` emits the actual 256x256 pretrained CNN, tests PyTorch/ONNX parity on three independently seeded tensors.
3. Checksum file binds the Android ONNX and LUT assets. No app network permission is added.
4. All Gradle Android unit tests run with fail-fast semantics (including new V6 damage checks and interpolation invariance).
5. APK built, `apksigner verify`, `zipalign -c -P 16`, artifact upload.

## Remaining gates before a qualified V6 release
- Install and save on actual devices (minSdk 24, mid-range, 16KB-page Android devices).
- Quantify processing latency and memory use at 4, 12, 48 MP; verify no OOM or ANRs.
- Benchmark at least 200 diverse legitimately available held-out photographs with source, actual V5, V6, and external auto-enhance references; multi-person blinded preferences.
- Need-to-edit category preference >65%, no-material-damage already-good rate >95% are initial targets, not measured results.
- Check normal and abnormal ICC primaries, high saturation, low light, RAW-origin imports, multiple subjects, skin; no source image-specific edits.
- Protect an unambiguous no-edit path and preserve authentic intentional high/low-key scenes.

## Limitations and risks
- A learned global LUT is not a semantic or spatially adaptive editor. Brightening the face independently from equally colored background pixels is not possible with this component alone.
- The output gate only screens measurable color/exposure damage. It is not a learned aesthetic evaluator.
- Global 72% blend can make edits mild, and safety screens can reject a candidate even if visually attractive. Benchmark first rather than weakening guards to produce visible change at any cost.
- First attempt may identify integration defects; any CI failures are release blockers. Never relabel an older V5 APK as V6.
