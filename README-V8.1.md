# Filter Slap V8.1 — Full-scene edits with independent regions

The global learned color and tone transformation is now applied to every valid image pixel, **irrespective of the saliency/foreground mask**. In V8, the mask artificially reduced the global learned-grade strength to 30% in background pixels before later tone/color protections. That mask-based global reduction is removed.

Localized controls remain selective by design: subject exposure/contrast, background exposure/contrast, highlight protection, noise reduction and fine detail are applied only as justified by tonal evidence. Background contrast is independently proposed only when the measured background has unusually compressed midtone range. The original/no-edit option still competes. No photo-specific image, object, color, position or test fixture is hardcoded.

The Edit Report now identifies the engine as `V8_AUTO_EDITOR`, calls candidate comparisons **Editing options**, and records the selected editing strategy. The new ARM64 debug APK version is `1.4.1-v8.1-beta` and is separately labeled in GitHub Actions.

## Verification gates

- Full Android unit tests, including source-independent color and foreground/background invariants.
- GitHub Actions APK build, signature and ZIP 16 KB alignment checks.
- Physical-device installation, compare, save and real-photo blind comparison: **not yet verified by CI**.

A global correction does not have to move every pixel equally: retaining pure black/white, highlights, and chromatic integrity is deliberate. The candidate scorer estimates technical defect correction and does not prove aesthetic superiority.
