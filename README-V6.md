# Filter Slap V6 — offline learned-enhancement Android integration candidate

V6 evaluates the original paired sRGB pretrained Image-Adaptive-3DLUT CNN (HuiZeng/Image-Adaptive-3DLUT; Apache-2.0 reference). The **pretrained assets are actually included inside the APK** after strict SHA-256 verification, ONNX conversion, and three PyTorch/ONNX numerical parity checks in GitHub Actions. The original V5 pixel engine is preserved and invoked if the V6 model is unavailable, lacks credible defect evidence, or fails a conservative *damage screen*. No uploaded photos or online inference calls are made by the installed Android app.

The global learned correction is applied with a 72% model-output blend. This is a safety limit, **not a guarantee of photographic quality**. The app does not yet provide spatially semantic enhancement or blindly validated aesthetic superiority. The learned transform is global. Foreground segmentation will not be enabled until proven with real-photo benchmarks; guessing a subject mask would be a regression.

## Real implementation

- `research/v6/export_android_model.py` exports the pinned actual PyTorch classifier to ONNX and LUTs to a raw little-endian tensor. Checks ONNX/PyTorch parity on three independent random inputs. Compiled model and LUTs are bundled under `app/src/main/assets` at cloud-build time; neither dummy checkpoints nor first-run downloads are permitted.
- `app/src/main/java/com/example/engine/v6/V6AdaptiveEditor.kt` uses ONNX Runtime Android to classify each photo to three basis coefficients and applies 33³ RGB LUT trilinear interpolation. All inference/rendering occurs on-device.
- `V6DecisionPolicy.kt` rejects outsized shifts, out-of-range proposals, highlight clipping, and excess desaturation. No-edit and V5 fallback remain available. This is a technical *damage filter*, not a photo-aesthetic scorer.
- Existing compare, save, crop, cancellation and history continue through the same Compose entry points.

## Acceptance status

- Source integration: implemented. CI required for verification.
- ONNX conversion and pretrained parity: CI required.
- Full Android unit tests, signed and aligned APK: CI required.
- Physical device install/runtime, memory profiling, varied real-photo blind preference: **not verified**.
- **Do not call V6 better looking than V5 without held-out real-photo evidence.**

Upstream pinned commit `b491f6df64a588864739a157db271e5c848e1805`; original software license `Apache-2.0`; dependency ONNX Runtime Android `MIT`. Model files downloaded only during trusted GitHub Actions builds from the upstream pinned commit and SHA-256 verified before packaging. Dataset/model weights' commercial permissions should be independently checked before retail distribution.
