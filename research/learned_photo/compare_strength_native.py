"""Exploratory strength attenuation vs unchanged V9 on same expert photo set.

This experiment was proposed AFTER observing the prior test set's safety
rejections, so an unchanged cohort is exploratory only. It cannot constitute
a fresh independent blind held-out evaluation or justify a release.
"""
from __future__ import annotations
import argparse,csv,json
from pathlib import Path
import numpy as np
from PIL import Image

def pixels(path):
    with Image.open(path) as f:
        return np.asarray(f.convert("RGB"),dtype=np.float32)/255.

def compare(root,model_output,native_output,baseline_output):
    summary_file=model_output/"public_aggregate_metrics.json"
    summary=json.loads(summary_file.read_text())
    mode=summary["winning_mode"]
    provenance=json.loads((root/"provenance-private.json").read_text())
    test_offset=int(provenance.get("test_offset",0))
    source_files=sorted((root/"pairs"/"test").glob("*_source.png"))
    if len(source_files)!=16:raise ValueError("Expected exactly sixteen test sources")
    with (native_output/"decisions.csv").open(newline="") as f:
        decisions=list(csv.DictReader(f))
    mapped={r["id"]:r for r in decisions}
    if len(mapped)!=16 or len(decisions)!=16:raise ValueError("Invalid native decision count")
    rows=[];errors=[]
    limits={"mean_change":35.,"new_clipping":.003,"new_crushing":.003,
        "hue_shift":.04,"vivid_boost":.04}
    for src_file in source_files:
        ident="fivek_"+src_file.name.removesuffix("_source.png")
        decision=mapped[ident]
        source=pixels(src_file)
        expert=pixels(root/"pairs"/"test"/(src_file.name.replace("_source.png","_expert.png")))
        v9=pixels(baseline_output/(ident+".png"))
        torch=pixels(model_output/mode/"test"/(ident+".png"))
        if not(source.shape==expert.shape==v9.shape==torch.shape):
            raise ValueError("Mismatched source, reference or baseline sizes")
        alpha=float(decision["strength"])
        if decision["decision"]=="accepted":
            if alpha not in {1.,.75,.5,.25,.125}:raise ValueError("Unrecognized strength")
            for key,limit in limits.items():
                if not float(decision[key])<=limit+1.e-7:
                    raise ValueError("Accepted edit exceeds existing V9 safety limit")
            native=pixels(native_output/(ident+".png"))
            if native.shape!=source.shape:raise ValueError("Android output geometry mismatch")
            expected=np.clip(source+alpha*(torch-source),0,1)
            errors.append(float(np.abs(expected-native).mean()))
            selected=native
        elif decision["decision"]=="rejected":
            if alpha!=0 or (native_output/(ident+".png")).exists():
                raise ValueError("Rejected edit cannot contain rendered output")
            selected=v9  # Preserve existing V9 editor on rejection
        else:raise ValueError("Unexpected candidate decision")
        def distance(a):return float(np.abs(a-expert).mean())
        rows.append({"strength":alpha,
            "v9":distance(v9),"selected":distance(selected),
            "learned_full":distance(torch)})
    if len(rows)!=16:raise ValueError("Missing evaluations")
    v9=np.array([x["v9"] for x in rows])
    selected=np.array([x["selected"] for x in rows])
    diff=v9-selected
    draws=np.random.default_rng(101).integers(0,len(diff),(4000,len(diff)))
    lo,hi=np.quantile(diff[draws].mean(1),[.025,.975])
    totals={}
    for row in rows:
        k=str(row["strength"])
        totals[k]=totals.get(k,0)+1
    result={
      "study":"exploratory attenuation using unchanged original V9 damage thresholds",
      "number_of_same_photos":16,
      "selection_does_not_read_expert_or_v9_image":True,
      "accepted_edit_count":len(errors),
      "fallback_to_v9_count":16-len(errors),
      "strength_distribution":totals,
      "mean_v9_expert_rgb_l1":float(v9.mean()),
      "mean_guarded_learned_or_v9_fallback_expert_rgb_l1":float(selected.mean()),
      "relative_l1_reduction_vs_v9":float(diff.mean()/max(v9.mean(),1.e-9)),
      "improved_cases":int((diff>1.e-5).sum()),
      "degraded_cases":int((diff<-1.e-5).sum()),
      "ties":int((np.abs(diff)<=1.e-5).sum()),
      "mean_paired_improvement_bootstrap_95pct":[float(lo),float(hi)],
      "android_vs_strength_scaled_torch_mean_rgb_l1":float(np.mean(errors)) if errors else None,
      "test_offset_records_in_official_testing_split":test_offset,
      "different_from_original_exploratory_cohort":test_offset>=64,
      "note":"Independent test range was predeclared in dataset preparation. Even independent RGB error is not a human preference or safety certificate.",
      "noncommercial_dataset_model_cannot_ship":True,
      "no_blind_human_quality_evidence":True,
      "approved_for_release":False}
    summary["guarded_learned_strength_exploratory"]=result
    summary_file.write_text(json.dumps(summary,indent=2)+"\n")
    print(json.dumps(result,indent=2),flush=True)
    return result

if __name__=="__main__":
    p=argparse.ArgumentParser()
    p.add_argument("--data",type=Path,required=True)
    p.add_argument("--models",type=Path,required=True)
    p.add_argument("--native",type=Path,required=True)
    p.add_argument("--v9",type=Path,required=True)
    a=p.parse_args()
    compare(a.data,a.models,a.native,a.v9)
