# Filter Slap V10: color fidelity engineering beta

## Scope implemented

- **Linear-light sRGB tone-only chroma control.** Brightness, contrast, and V9 local shadow recovery no longer multiply or exaggerate the source's color component when no explicit grade or white-balance correction is chosen. The renderer preserves the source linear RGB chroma direction and candidate linear luminance; near-gamut edits reduce chroma rather than clipping one channel. The transform is frame-general, not image-specific.
- **Perceptual OKLab audit of all rendered preview candidates**, independently of which strategy generated them. Excessive chroma increases, substantial hue drift, and newly introduced casts on near-neutral pixels now count against the candidate's score. The existing safety gates and original-photo fallback remain.
- **Performance-conscious transfer tables** replace per-channel exponentiation in the full-image tone stabilization step; perceptual color evaluation runs on sampled preview pixels, not every full-resolution output pixel.
- Debug package `1.6.0-v10-beta` retains offline pretrained LUT, saliency masks, V9 spatial lighting, before/after interface and JPEG export.

## Qualification limits

This is a **color-fidelity engineering milestone**, not evidence of the complete V10 roadmap or professional photographic improvement. Existing scene-understanding weaknesses, uncertain candidate aesthetic ranking, preview-versus-full-resolution interpolation, wide-gamut/HDR input handling, output dimension caps, and limited device tests are not solved in this patch.

No sample images, color/material presets, object coordinates, photo IDs or per-photo branches were added. Synthetic regression tests establish color-direction and gamut invariants; independent unseen-photo preference testing is still required.

## Required build gates

Android unit tests must run without `continue-on-error`; APK must compile and pass signature/alignment checks before being considered a debug candidate. Physical phone tests and blinded quality comparisons remain separate gates.