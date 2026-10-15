"""Exact held-out Expert C RGB comparisons for actual V9 and learned output.

Research metric only. Pixel L1 is not a measure of visual professionalism.
Inputs/weights/photos remain on an ephemeral runner and are never uploaded.
"""
from __future__ import annotations
import argparse
import json
from pathlib import Path
import numpy as np
from PIL import Image

def pixels(path):
    with Image.open(path) as im:
        if im.mode not in ("RGB","RGBA"):raise ValueError("Unexpected image format")
        return np.asarray(im.convert("RGB"),dtype=np.float32)/255.

def compare(data:Path, models:Path, v9:Path):
    file=models/"public_aggregate_metrics.json"
    report=json.loads(file.read_text())
    winner=report["winning_mode"]
    originals=sorted((data/"pairs"/"test").glob("*_source.png"))
    if len(originals)!=report["n_heldout_test"]:raise ValueError("Held-out size mismatch")
    errors=[]
    for source_path in originals:
        idnum=source_path.name.removesuffix("_source.png")
        ident="fivek_"+idnum
        original=pixels(source_path)
        expert=pixels(data/"pairs"/"test"/(idnum+"_expert.png"))
        baseline=pixels(v9/(ident+".png"))
        learned=pixels(models/winner/"test"/(ident+".png"))
        if not(original.shape==expert.shape==baseline.shape==learned.shape):
            raise ValueError("One model/baseline changed source geometry")
        def l1(x):return float(np.mean(np.abs(x-expert)))
        errors.append([l1(original),l1(baseline),l1(learned)])
    a=np.asarray(errors,dtype=np.float64)
    margin=a[:,1]-a[:,2]
    rng=np.random.default_rng(27)
    intervals=np.mean(margin[rng.integers(0,len(a),(4000,len(a)))],axis=1)
    lo,hi=np.quantile(intervals,[.025,.975])
    report["same_photos_v9_comparison"]={
        "baseline":"actual unmodified Android V9 OfflineEnhancer executed in Robolectric",
        "n":int(len(a)),
        "unedited_mean_expert_rgb_l1":float(a[:,0].mean()),
        "v9_mean_expert_rgb_l1":float(a[:,1].mean()),
        "learned_mean_expert_rgb_l1":float(a[:,2].mean()),
        "learned_relative_error_reduction_vs_v9":float(margin.mean()/max(1e-8,a[:,1].mean())),
        "learned_l1_better_than_v9_cases":int((margin>1e-5).sum()),
        "v9_l1_better_than_learned_cases":int((margin<-1e-5).sum()),
        "near_tie_cases":int((np.abs(margin)<=1e-5).sum()),
        "mean_paired_l1_margin_v9_minus_learned":float(margin.mean()),
        "paired_mean_margin_bootstrap_95pct":[float(lo),float(hi)],
        "human_photo_preference_not_measured":True,
        "v9_run_was_robolectric_not_real_android_device":True,
        "release_authorized":False}
    if abs(report["heldout_test"]["model_expert_l1"]-a[:,2].mean())>.003:
        raise ValueError("Learned output changed relative to the documented research experiment")
    file.write_text(json.dumps(report,indent=2)+"\n")
    print(json.dumps(report["same_photos_v9_comparison"],indent=2),flush=True)
    return report["same_photos_v9_comparison"]

if __name__=="__main__":
    p=argparse.ArgumentParser()
    p.add_argument("--data",type=Path,required=True)
    p.add_argument("--models",type=Path,required=True)
    p.add_argument("--v9",type=Path,required=True)
    a=p.parse_args()
    compare(a.data,a.models,a.v9)
