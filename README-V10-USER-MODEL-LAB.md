# V10 experimental offline ONNX model lab

**Status: debug-only technical testing. NOT a professional AI editing release.**

Normal app entry path is unchanged and still uses `V9_3_COHERENT_TONE`.
A separate **Experimental offline AI lab** action is visible on the welcome screen
only for debug builds. There are **no expert-trained learned ONNX models
bundled** in this APK, because our MIT–Adobe FiveK research checkpoint is
licensed for research use and its distribution rights have not been cleared.

## What the lab actually does

1. Tap **Experimental offline AI lab** on the normal welcome page.
2. **Import local ONNX model** via Android's system document picker. Only the
   exact `learned_global_predictor.onnx` architecture
   ([1,3,256,256] sRGB float input named `srgb_context`,
   [1,12,8,1,1] float output named `guided_affine_grid`) is supported.
   You must have permission to use and distribute any imported model.
3. Model is SHA-256-pinned, copied to app-private storage, and revalidated
   before export. Models >12MB, incompatible architectures and corrupted bytes
   are rejected; zero external network connections are used.
4. **Choose photo** and **Test this AI on photo**.
   The original is shown until a candidate passes unchanged V9 damage limits.
   Hold to compare original. Exact chosen strength (1, .75, .5, .25, .125) is
   displayed. Editing is independent of any test-image filename or category.
5. **Save full-resolution AI copy** decodes the same image using the
   existing memory-bounded gallery export decoder and recomputes the
   predictor using the **same, locked candidate strength**.
   If final full-size safety fails, export returns an error and saves
   **nothing**; it does not lie by silently exporting the legacy baseline.
   Existing gallery saving preserves EXIF orientation at decode and writes
   JPEG 95, consistent with the standard app.
6. Back in the normal editor, all original UI, photo analysis, V9.3 editing,
   crop options, original comparison, reports, history, and exports remain
   untouched. The lab cannot silently become the default.

## Verification

Workflow `.github/workflows/build-debug-apk.yml` performs:
- original V9.3 real-photo regressions and existing full Android unit tests;
- synthetic but **real executable ONNX** model checks (import, rejection,
  hash verification, native preview/export parity);
- full Android assembleDebug, apk signing, alignment and version checks;
- explicit check that no unapproved `learned_*_predictor.onnx` asset was
  bundled inside the application APK.

These are engineering/compatibility tests, **not a proof of better edits**.
Native trained-model FiveK research previously reached 21.2% lower average
RGB error vs V9 on a different 16-photo cohort, but **5/16 degraded**,
and there is no blinded human preference study or model redistribution grant.
Therefore this APK **must not be presented as a quality upgrade**.

## Remaining blockers for a usable general-public learned editor

- Obtain paired expert edits with documentation explicitly granting
  deployment and distribution of trained model weights, independent of
  the dataset code's open-source license.
- Train at scale, evaluate on fresh, diverse original photos, and
  conduct genuine blind human review including low light, existing good
  photos, portraits, dark materials, highlights, skies and architecture.
- Measure model safety at **actual export resolution**, preview/export parity,
  device memory, thermal behavior and airplane mode on physical Android
  hardware.
- Only then make the learned model the **normal auto enhancement engine**.

**No APK from this branch should replace the known-good V9.3 release by default.**
