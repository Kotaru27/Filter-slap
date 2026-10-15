"""Aggregate native Android ONNX parity against trained PyTorch outputs.

Run only inside research CI with licensed input/outputs kept private on the
ephemeral runner; update public aggregate metrics, never upload photographs.
"""
from __future__ import annotations
import argparse
import csv
import json
from pathlib import Path
import numpy as np
from PIL import Image

def rgb(path:Path)->np.ndarray:
    with Image.open(path) as im:
        return np.asarray(im.convert("RGB"),dtype=np.float32)/255.

def aggregate(model_root:Path,android_dir:Path,source_root:Path)->dict:
    reportfile=model_root/"public_aggregate_metrics.json"
    summary=json.loads(reportfile.read_text())
    winner=summary["winning_mode"]
    source_paths=sorted((source_root/"pairs"/"test").glob("*_source.png"))
    if len(source_paths)!=16:raise ValueError("Expected exactly 16 holdout photos")
    decisions={}
    with (android_dir/"decisions.csv").open(newline="") as f:
        for row in csv.DictReader(f):
            ident=row["id"]
            if ident in decisions or row["decision"] not in {"accepted","rejected"}:
                raise ValueError("Invalid or duplicated native Android decision")
            decisions[ident]=row
    parity=[];native_expert=[];v9_expert=[]
    expected_names={"fivek_"+p.stem.removesuffix("_source") for p in source_paths}
    if set(decisions)!=expected_names:raise ValueError("Missing or unexpected research cases")
    rejections=0
    for ident in sorted(decisions):
        if decisions[ident]["decision"]=="rejected":
            rejections+=1
            if (android_dir/(ident+".png")).exists():
                raise ValueError("Rejected photo mistakenly exported")
            continue
        android=rgb(android_dir/(ident+".png"))
        torch=rgb(model_root/winner/"test"/(ident+".png"))
        expert=rgb(source_root/"pairs"/"test"/(ident.removeprefix("fivek_")+"_expert.png"))
        v9=rgb(Path("/tmp/actual-v9-outputs")/(ident+".png"))
        if not (android.shape==torch.shape==expert.shape==v9.shape):
            raise ValueError("Cross-platform image shape mismatch")
        parity.append(float(np.abs(android-torch).mean()))
        native_expert.append(float(np.abs(android-expert).mean()))
        v9_expert.append(float(np.abs(v9-expert).mean()))
    if not parity:raise ValueError("All trained candidates rejected by Android")
    result={
        "research_only":True,
        "true_trained_onnx_used_in_android_robolectric":True,
        "total_test_cases":len(source_paths),
        "native_accepted":len(parity),
        "native_safety_rejected":rejections,
        "mean_native_vs_torch_rgb_l1":float(np.mean(parity)),
        "max_case_native_vs_torch_rgb_l1":float(np.max(parity)),
        "native_parity_under_2_of_255_mean":bool(np.mean(parity)<2./255.),
        "native_accepted_mean_expert_rgb_l1":float(np.mean(native_expert)),
        "v9_same_accepted_mean_expert_rgb_l1":float(np.mean(v9_expert)),
        "native_vs_v9_gain_on_accepted_subcohort":
            float(np.mean(v9_expert)-np.mean(native_expert)),
        "human_visual_preferences_measured":False,
        "real_device_verified":False,
        "model_redistribution_permitted":False,
        "android_release_approved":False
    }
    summary["native_android_trained_onnx_comparison"]=result
    reportfile.write_text(json.dumps(summary,indent=2)+"\n")
    print(json.dumps(result,indent=2),flush=True)
    return result

if __name__=="__main__":
    p=argparse.ArgumentParser()
    p.add_argument("--model-root",type=Path,required=True)
    p.add_argument("--android",type=Path,required=True)
    p.add_argument("--source",type=Path,required=True)
    a=p.parse_args()
    aggregate(a.model_root,a.android,a.source)
