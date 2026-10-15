#!/usr/bin/env bash
set -euo pipefail
# Run only in an isolated checkout. Writes staged TEST sources, never an APK.
ROOT="${AUDIT_ROOT:?Set an absolute private research directory}"
PR_REV=2229cb55108d400a86e188df10b84c0d0f44cd06
V93_REV=03e587725058dcf31316e3fab19bff6c4677ffc2
mkdir -p "$ROOT/public" "$ROOT/visuals"
python research/learned_photo/prepare_fivek_pilot.py --root "$ROOT/reproduction" \
  --train 64 --val 16 --test 16 --max-edge 384 --test-offset 64
python research/learned_photo/prepare_audit_cohort.py --previous "$ROOT/reproduction" \
  --root "$ROOT/fresh" --offset 128 --count 16 --full-count 4
python research/learned_photo/run_real_pilot.py --manifest "$ROOT/reproduction/manifest.csv" \
  --out "$ROOT/models" --epochs 6 --min-train 64 --min-val 16 --min-test 16
python - <<'PY'
import os,json,shutil,hashlib,platform,importlib.metadata
from pathlib import Path
from research.learned_photo.evaluate import evaluate,load,render
from PIL import Image
root=Path(os.environ['AUDIT_ROOT'])
summary=json.loads((root/'models/public_aggregate_metrics.json').read_text())
mode=summary['winning_mode']; checkpoint=root/'models'/mode/'best.pt'
(root/'mode.txt').write_text(mode)
evaluate(root/'fresh/manifest.csv',checkpoint,root/'fresh-models'/mode/'test','test',16)
(root/'fresh-models/public_aggregate_metrics.json').write_text(json.dumps(summary))
model=load(checkpoint)
out=root/'full-models'/mode/'test';out.mkdir(parents=True)
full_sources=sorted((root/'fresh/full/test').glob('*_source.png'))
for path in full_sources:
    ident='fivek_'+path.stem.removesuffix('_source')
    with Image.open(path) as im:render(model,im.convert('RGB'),tile_rows=64).save(out/(ident+'.png'))
full_summary=dict(summary,n_heldout_test=len(full_sources))
(root/'full-models/public_aggregate_metrics.json').write_text(json.dumps(full_summary))
(root/'full-data/pairs').mkdir(parents=True)
(root/'full-data/pairs/test').symlink_to(root/'fresh/full/test',target_is_directory=True)
shutil.copy2(root/'models/public_aggregate_metrics.json',root/'public/training-summary.json')
env={'python':platform.python_version(),'packages':{k:importlib.metadata.version(k) for k in ['torch','numpy','Pillow','rawpy']},
     'checkpoint_sha256':hashlib.sha256(checkpoint.read_bytes()).hexdigest(),
     'experiment_commit':os.environ.get('GITHUB_SHA'),'fresh_test_offset':128,
     'no_training_on_fresh_cohort':True,'no_candidate_policy_change':True}
(root/'public/reproducibility.json').write_text(json.dumps(env,indent=2))
for cohort in ('reproduction','fresh'):
    shutil.copy2(root/cohort/'provenance-private.json',root/'public'/f'{cohort}-capture-provenance.json')
PY
# Match original exporter/runtime torch version after training.
python -m pip install 'torch==2.6.0' --index-url https://download.pytorch.org/whl/cpu
python research/v6/fetch_weights.py --output research/v6/model_weights
python research/v6/export_android_model.py --model-dir research/v6/model_weights --output-dir app/src/main/assets
python research/v7/prepare_assets.py --output-dir app/src/main/assets
MODE="$(cat "$ROOT/mode.txt")"
python research/learned_photo/export_onnx.py --checkpoint "$ROOT/models/$MODE/best.pt" --out "$ROOT/onnx-private"
git fetch --no-tags origin "$PR_REV" "$V93_REV"
mkdir -p app/src/main/java/com/example/engine/learned app/src/test/java/com/example/engine/learned
for name in LearnedOfflineCandidate LearnedCoefficientGrid; do
  git show "$PR_REV:app/src/main/java/com/example/engine/learned/$name.kt" > "app/src/main/java/com/example/engine/learned/$name.kt"
done
cp research/learned_photo/RegressionEvidenceTest.kt app/src/test/java/com/example/engine/learned/
git show "$PR_REV:app/src/test/java/com/example/engine/learned/LearnedStrengthSafetyTest.kt" \
  > app/src/test/java/com/example/engine/learned/LearnedStrengthSafetyTest.kt
chmod +x gradlew
function native_audit() {
  AUDIT_SOURCE="$1" AUDIT_OUTPUT="$2" AUDIT_COUNT="$3" AUDIT_ENGINE="$4" \
    AUDIT_MODEL="$ROOT/onnx-private/learned_${MODE}_predictor.onnx" AUDIT_MODE="$MODE" \
    ./gradlew :app:cleanTestDebugUnitTest :app:testDebugUnitTest --tests '*RegressionEvidenceTest' --tests '*LearnedStrengthSafetyTest' \
      --no-daemon --stacktrace --no-configuration-cache
}
native_audit "$ROOT/reproduction/pairs/test" "$ROOT/native-reproduction" 16 V9_SPATIAL
native_audit "$ROOT/fresh/pairs/test" "$ROOT/native-fresh" 16 V9_SPATIAL
native_audit "$ROOT/fresh/full/test" "$ROOT/native-full" 4 V9_SPATIAL
# Only the EXACT five reference engine files differ; no bespoke V9.3 port.
for path in v7/EnhancementMath v7/OfflineEnhancer v8/CandidatePlanner v9/ChromaticIntegrity v9/CoherentTone; do
  git show "$V93_REV:app/src/main/java/com/example/engine/$path.kt" > "app/src/main/java/com/example/engine/$path.kt"
done
native_audit "$ROOT/reproduction/pairs/test" "$ROOT/v93-reproduction" 16 V9_3_COHERENT_TONE
native_audit "$ROOT/fresh/pairs/test" "$ROOT/v93-fresh" 16 V9_3_COHERENT_TONE
native_audit "$ROOT/fresh/full/test" "$ROOT/v93-full" 4 V9_3_COHERENT_TONE
python research/learned_photo/regression_audit.py --data "$ROOT/reproduction" --models "$ROOT/models" \
  --native "$ROOT/native-reproduction/learned" --v9 "$ROOT/native-reproduction/baseline" \
  --v93 "$ROOT/v93-reproduction/baseline" --out "$ROOT/public/reproduction-audit.json" --visuals "$ROOT/visuals/reproduction"
python research/learned_photo/regression_audit.py --data "$ROOT/fresh" --models "$ROOT/fresh-models" \
  --native "$ROOT/native-fresh/learned" --v9 "$ROOT/native-fresh/baseline" \
  --v93 "$ROOT/v93-fresh/baseline" --out "$ROOT/public/fresh-audit.json" --visuals "$ROOT/visuals/fresh"
python research/learned_photo/regression_audit.py --data "$ROOT/full-data" --models "$ROOT/full-models" \
  --native "$ROOT/native-full/learned" --v9 "$ROOT/native-full/baseline" \
  --v93 "$ROOT/v93-full/baseline" --out "$ROOT/public/full-audit.json" --visuals "$ROOT/visuals/full"
python research/learned_photo/finish_regression_audit.py --root "$ROOT"
python research/learned_photo/seal_audit_visuals.py --folder "$ROOT/visuals" \
  --public-key research/learned_photo/audit-visual-public.pem --output "$ROOT/sealed"
# Explicit allowlists: no model/checkpoint/photo directory is uploaded.
test ! -e app/src/main/assets/learned_global_predictor.onnx
test ! -e app/src/main/assets/learned_spatial_predictor.onnx
