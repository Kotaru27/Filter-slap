"""Run BOTH real paired-photograph pilots, select on validation only.

No checkpoint, photograph, or expert target is uploaded as a public artifact.
This reports test-set metrics only once, with explicit no-release status.
"""
from __future__ import annotations
import argparse
import json
from pathlib import Path
import numpy as np
import torch
try:
    from .train import train
    from .data import read_manifest
    from .evaluate import evaluate
except ImportError:
    from train import train
    from data import read_manifest
    from evaluate import evaluate

def run(manifest:Path,out:Path,epochs:int=4):
    torch.set_num_threads(2)
    rows=read_manifest(manifest,min_train=12,min_val=4,min_test=4)
    out.mkdir(parents=True,exist_ok=True)
    candidates={}
    for mode in ("global","spatial"):
        directory=out/mode
        summary=train(manifest,directory,mode,epochs=epochs,batch=2,
            min_train=12,min_val=4,min_test=4)
        validation=evaluate(manifest,directory/"best.pt",directory/"validation","val",4)
        candidates[mode]={"training":summary,"validation":validation}
    # Freeze selection using only validation objective. Test images are unseen
    # by both the optimizer and the selection decision.
    winner=min(candidates,key=lambda name:candidates[name]["training"]["best_validation_objective"])
    result=evaluate(manifest,out/winner/"best.pt",out/winner/"test","test",4)
    # A tiny pilot cannot establish visual aesthetic gains; for this experiment
    # calculate only aggregate expert-target distance, never a release score.
    improvement=(result["mean_unedited_l1_to_expert"]-
                 result["mean_model_l1_to_expert"])
    report={
        "pilot_type":"MIT-Adobe FiveK research-only Expert C",
        "n_train":sum(x.split=="train" for x in rows),
        "n_val":sum(x.split=="val" for x in rows),
        "n_heldout_test":sum(x.split=="test" for x in rows),
        "model_selection_based_on":"validation objective only",
        "winning_mode":winner,
        "candidates":{k:{"best_validation_objective":v["training"]["best_validation_objective"],
             "validation_model_expert_l1":v["validation"]["mean_model_l1_to_expert"],
             "validation_unedited_expert_l1":v["validation"]["mean_unedited_l1_to_expert"]}
             for k,v in candidates.items()},
        "heldout_test":{"model_expert_l1":result["mean_model_l1_to_expert"],
             "unedited_expert_l1":result["mean_unedited_l1_to_expert"],
             "l1_reduction":improvement,
             "l1_relative_reduction":improvement/max(1e-8,result["mean_unedited_l1_to_expert"])},
        "expert_l1_is_not_professional_quality":True,
        "not_compared_to_actual_v9_apk_exports":True,
        "no_blinded_human_preferences":True,
        "research_license_not_cleared_for_app_distribution":True,
        "app_modified":False,
        "approved_for_android":False,
        "model_weights_not_redistributed":True
    }
    (out/"public_aggregate_metrics.json").write_text(json.dumps(report,indent=2)+"\n")
    print(json.dumps(report,indent=2),flush=True)
    return report

if __name__=="__main__":
    p=argparse.ArgumentParser()
    p.add_argument("--manifest",type=Path,required=True)
    p.add_argument("--out",type=Path,required=True)
    p.add_argument("--epochs",type=int,default=4)
    a=p.parse_args()
    run(a.manifest,a.out,a.epochs)
