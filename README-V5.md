# Filter Slap V5 — photographic-fidelity candidate

V5 prioritizes **not degrading already-balanced photographs**. It is not yet certified as a professional-quality photo editor. Unit tests and a successful APK build are engineering gates, not visual-quality proof.

Changes:

1. Replaced global edge-channel desaturation with local chroma-fringe anomaly correction. Both sides of a luminance edge must agree in normalized color; an ordinary colored-object edge is never sufficient evidence to change a pixel. Five-row streaming means no full-frame extra copy.
2. Removed the automatic aesthetic split-tone and background desaturation/contrast decisions. The existing renderer functionality remains for separately verified future use, but scene detection alone cannot authorize it.
3. Restricted automatic contrast and black-point stretching to measurable narrow tonal deficiencies. Scene category changes bounds, not the existence of a correction.
4. Improved report honesty: rejected CA plans are removed from the final applied-plan list; strength percentages are explicitly not quantitative claims of quality improvement; upper-edge orientation is labeled as an uncertain estimate, not as a measured horizon defect.
5. Added focused regression tests that include shifted-channel color fringes, legitimate colored boundaries, healthy tonal distributions, and automatic styling abstention.

## Verification gates

- Full GitHub Actions `testDebugUnitTest`: **pending for V5**.
- Genuine Android debug build, `apksigner verify`, and 16 KB `zipalign`: **pending for V5**.
- Device installation, photo saving, cancellation/lifecycle behavior: **not independently verified for V5**.
- Blind visual preference testing on diverse held-out photographs: **not performed**.

There are no image-specific color/coordinate/object hacks. Tests use synthetic scenes as general mathematical counterexamples, not templates for edits.
