# Changes in this audited source revision

## From the earlier corrected source

- Repaired crop calculations at narrow/tiny dimensions; always clamp cropped width and height to at least 1 pixel.
- Stop treating missing `Subject`/`Texture` masks as full-frame permission to edit (mask fallbacks now zero).
- Avoid unnecessary HSL round-trips when measured noise/chroma modification is absent.
- Strengthen edit-safety iteration by attenuating the current candidate repeatedly; removed a flawed cumulative ledger that could stop reduction with harmful corrections still active.
- Include clarity/texture in the tonal safety gate; use a conservative final-stage failure threshold. This is a heuristic gate, not a quality guarantee.
- Add a pure-function property test for progressively attenuating edit magnitudes. Do not interpret synthetic invariants as photo aesthetics.
- Restrain several **optional** color/tone styling effects on already-healthy frames, without replacing defect corrections. Preserved the pre-existing detail/clarity policy after a regression test caught an overbroad gate.
- Fix history metadata to capture the profile and edit decisions actually used during **export**, which can differ from the preview-render plan.
- Verify MediaStore pending-to-published transition (Android Q+); close decode/EXIF input streams; reject export failures rather than misreport success.
- Fix comparison framing when crop changes; make crop choices accessible through a horizontal scrollable row; remove an unused previously rendered bitmap variable.
- Report lens corrections as applied only when the *applied plan* contains that correction. Distinguish a reviewed/no-edit frame from an edited frame.
- Remove unnecessary Google Services/Firebase build components that the offline app did not use.
- Improve CI: Android SDK setup, run JUnit before assemble, validate signed APK and 16-KB library alignment, upload results after success.

## What this revision does not accomplish

No new neural scene segmentation, no verified professional-quality image output, no proof of real device functionality, no source-compiled APK, and **no resolved global tint-versus-foliage ambiguity**. See `VALIDATION-STATUS.md`. Changes to the decision engine are not image-specific patches or exceptions for the photographed examples used earlier.
