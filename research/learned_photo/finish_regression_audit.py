"""Record reproducibility, engine identity, scale consistency and blind materials."""
import argparse
import csv
import hashlib
import json
import shutil
import urllib.request
from pathlib import Path
import numpy as np
from PIL import Image
try:
    from .evaluate import blind_cards
except ImportError:
    from evaluate import blind_cards


def finish(root):
    old=json.loads((root/'public/reproduction-audit.json').read_text())
    # Original artifact's old-V9 comparison, expressed in byte levels.
    comparison=old['comparisons']['v9']
    reference={'v9':.11970259086228907*255,'guarded':.09433001815341413*255}
    reproduction={
        'original_run':38770285292,
        'v9_mean_absolute_difference_levels':abs(comparison['mean_baseline_rgb_l1']-reference['v9']),
        'guarded_mean_absolute_difference_levels':abs(comparison['mean_guarded_rgb_l1']-reference['guarded']),
        'regression_count_matches_original':len(comparison['degraded_ids'])==5,
        'exact_original_checkpoint_unavailable':True}
    reproduction['aggregate_reproduced_within_0_1_levels']=(
        reproduction['v9_mean_absolute_difference_levels']<.1 and
        reproduction['guarded_mean_absolute_difference_levels']<.1 and
        reproduction['regression_count_matches_original'])
    (root/'public/original-reproduction-check.json').write_text(json.dumps(reproduction,indent=2)+'\n')
    for label in ('native-reproduction','native-fresh','native-full','v93-reproduction','v93-fresh','v93-full'):
        shutil.copy2(root/label/'engine_provenance.csv',root/'public'/f'{label}-engine.csv')
    rows=[]
    full_decisions={r['id']:r for r in csv.DictReader((root/'native-full/learned/decisions.csv').open())}
    preview_decisions={r['id']:r for r in csv.DictReader((root/'native-fresh/learned/decisions.csv').open())}
    for ident,decision in full_decisions.items():
        for label,pre,full in [('learned','native-fresh/learned','native-full/learned'),
                               ('v93','v93-fresh/baseline','v93-full/baseline')]:
            if label=='learned' and (decision['decision']!='accepted' or preview_decisions[ident]['decision']!='accepted'):
                rows.append({'id':ident,'engine':label,'accepted_at_both_sizes':False});continue
            with Image.open(root/pre/(ident+'.png')) as p,Image.open(root/full/(ident+'.png')) as f:
                delta=np.abs(np.asarray(p.convert('RGB'),np.float32)-np.asarray(f.resize(p.size,Image.Resampling.LANCZOS),np.float32))
                rows.append({'id':ident,'engine':label,'full_size':f.size,'preview_size':p.size,
                    'mean_rgb_difference_levels':float(delta.mean()),'p99_channel_difference_levels':float(np.quantile(delta,.99)),
                    'preview_strength':float(preview_decisions[ident]['strength']) if label=='learned' else None,
                    'full_strength':float(decision['strength']) if label=='learned' else None})
    (root/'public/scale-consistency.json').write_text(json.dumps({'cases':rows,
        'resampling_and_nonlinearity_contribute_to_difference':True,
        'real_device_or_apk_export_verified':False},indent=2)+'\n')
    for cohort in ('reproduction','fresh'):
        # Copy fallback for a rejected candidate before calling existing blind protocol.
        challenge=root/f'blind-selected-{cohort}'; challenge.mkdir()
        decisions=csv.DictReader((root/f'native-{cohort}/learned/decisions.csv').open())
        for row in decisions:
            folder='learned' if row['decision']=='accepted' else 'baseline'
            shutil.copy2(root/f'native-{cohort}'/folder/(row['id']+'.png'),challenge/(row['id']+'.png'))
        cards=root/'visuals'/f'blind-{cohort}'
        blind_cards(root/cohort/'manifest.csv',challenge,root/f'v93-{cohort}/baseline',cards,root/f'private-blind-{cohort}-key.json',16)
        # A blank template is numeric/text evidence, not fabricated reviewer votes.
        shutil.move(cards/'votes_template.csv',root/'public'/f'blind-{cohort}-votes-template.csv')
    notices=root/'visuals'/'research-license-notices.txt'
    terms=[]
    for name in ('LicenseAdobe.txt','LicenseAdobeMIT.txt'):
        url='https://data.csail.mit.edu/graphics/fivek/legal/'+name
        terms.append(url+'\n'+urllib.request.urlopen(url,timeout=30).read().decode())
    notices.write_text('PRIVATE RESEARCH REVIEW ONLY. NO MODEL DISTRIBUTION.\n\n'+'\n\n'.join(terms))
    # Fingerprints identify frozen source; no trained coefficients are exposed.
    source_paths=list(Path('app/src/main/java/com/example/engine/learned').glob('*.kt'))
    source_paths += [Path('app/src/main/java/com/example/engine')/p for p in
        ('v7/EnhancementMath.kt','v7/OfflineEnhancer.kt','v8/CandidatePlanner.kt','v9/ChromaticIntegrity.kt','v9/CoherentTone.kt')]
    (root/'public/engine-source-hashes.json').write_text(json.dumps({str(p):hashlib.sha256(p.read_bytes()).hexdigest() for p in source_paths},indent=2)+'\n')


if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--root',type=Path,required=True)
    finish(p.parse_args().root)
