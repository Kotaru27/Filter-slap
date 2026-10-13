# V5 verification record

Base commit: `5c618f19c4cfb40882d92712710178e039da0cde` (V4, 199 passing Android unit tests).

## Local evidence

- New correction implementation `ChromaticFringeCorrection.kt` compiled with standalone Kotlin compiler.
- A direct Kotlin smoke probe: simulated two-pixel channel displacement changed 128 edge pixels in a 96x64 sample, reducing the fringe RGB imbalance. Four different layouts of unrelated colored-object edges changed zero pixels. **This is not a real-photo benchmark.**
- Previous physical bug: V4 changed red/blue on every detail-mask edge after a global CA decision, even where there was no chromatic anomaly.

## Known limitations

- A narrow, isolated colored feature surrounded by neutral sides is theoretically ambiguous from an optical color fringe. The candidate correction is deliberately capped and must still be judged visually.
- This routine does not reconstruct missing optical detail, and does not claim semantic scene understanding.
- No original photograph was used to derive a pixel threshold or branch.
- Pixel-level and compositional photo-quality improvement across scenes remains unverified.
- The frame-level analyzer can still overestimate chromatic aberration; the local renderer abstains unless evidence exists.

## Acceptance criteria

- Full CI unit tests pass without skipping or concealing failures.
- APK compilation, signing, and native-library alignment checks pass.
- The editing application installs, renders, compares, and saves photographs correctly on Android devices.
- An independent set of varied photos shows no systematic desaturation or contrast degradation and no new artifacts.

## First V5 CI attempt — failed, investigated

GitHub Actions run `38427113776` compiled/signed an APK but failed 1 of 203 tests: the near-flat contrast "dead zone" scenario used `iqr=0.165`, `occupancy=0.165`. A newly introduced `occupancy >0.25` gate had incorrectly suppressed a justified modest contrast correction. Corrected the gate to `>0.15`, preserving the existing test as an independent constraint. Changed CI to **stop at failing tests**, instead of rendering a `continue-on-error` test step as successful and failing at a separate terminal gate. No unverified first-run APK is being distributed. Re-run pending.
