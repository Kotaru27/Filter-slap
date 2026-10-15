"""Train the learned photo engine on lawfully owned, aligned expert pairs.

This DOES NOT change Android, auto-download datasets or approve photography.
"""
import argparse
import hashlib
import json
import random
from pathlib import Path
import numpy as np
import torch
from torch.utils.data import DataLoader
try:
    from .data import ExpertPairs, read_manifest
    from .model import LearnedPhotoEditor, objective
except ImportError:
    from data import ExpertPairs, read_manifest
    from model import LearnedPhotoEditor, objective

def train(manifest:Path,out:Path,mode:str,epochs:int=30,batch:int=4,
          seed:int=27,lr:float=2e-4,min_train:int=50,
          min_val:int=20,min_test:int=20)->dict:
    if epochs<1 or batch<1 or lr<=0:
        raise ValueError("invalid training settings")
    rows=read_manifest(manifest,min_train,min_val,min_test)
    random.seed(seed);np.random.seed(seed);torch.manual_seed(seed)
    device=torch.device("cuda" if torch.cuda.is_available() else "cpu")
    net=LearnedPhotoEditor(mode).to(device)
    opt=torch.optim.AdamW(net.parameters(),lr=lr,weight_decay=1e-4)
    train_data=ExpertPairs(rows,"train",seed=seed)
    val_data=ExpertPairs(rows,"val",seed=seed)
    batches=DataLoader(train_data,batch_size=batch,shuffle=True,
                       generator=torch.Generator().manual_seed(seed),num_workers=0)
    validation=DataLoader(val_data,batch_size=batch,shuffle=False,num_workers=0)
    out.mkdir(parents=True,exist_ok=True)
    log=[];best=float("inf")
    digest=hashlib.sha256(manifest.read_bytes()).hexdigest()
    for epoch in range(epochs):
        net.train()
        total=count=0
        for source,expert,preserve in batches:
            source,expert,preserve=source.to(device),expert.to(device),preserve.to(device)
            opt.zero_grad(set_to_none=True)
            prediction=net(source)
            loss,_=objective(source,prediction,expert,preserve)
            if not torch.isfinite(loss):raise FloatingPointError("nonfinite loss")
            loss.backward()
            torch.nn.utils.clip_grad_norm_(net.parameters(),2.)
            opt.step()
            total+=float(loss.detach())*source.shape[0]
            count+=source.shape[0]
        net.eval()
        val_total=val_count=val_l1=baseline_l1=0
        with torch.inference_mode():
            for source,expert,preserve in validation:
                source,expert,preserve=source.to(device),expert.to(device),preserve.to(device)
                pred=net(source)
                loss,_=objective(source,pred,expert,preserve)
                val_total+=float(loss)*source.shape[0]
                val_l1+=float((pred-expert).abs().flatten(1).mean(1).sum())
                baseline_l1+=float((source-expert).abs().flatten(1).mean(1).sum())
                val_count+=source.shape[0]
        row={"epoch":epoch+1,"train_objective":total/count,
             "validation_objective":val_total/val_count,
             "validation_l1_to_expert":val_l1/val_count,
             "unedited_validation_l1_to_expert":baseline_l1/val_count}
        log.append(row)
        print(json.dumps(row),flush=True)
        if row["validation_objective"]<best:
            best=row["validation_objective"]
            torch.save({"format":"filter-slap-learned-v1","mode":mode,
                        "model":{k:v.detach().cpu() for k,v in net.state_dict().items()},
                        "manifest_sha256":digest,"best_validation_objective":best,
                        "epoch":epoch+1,"seed":seed,
                        "train_count":len(train_data),"val_count":len(val_data),
                        "weights_not_authorized_for_app":True},out/"best.pt")
    (out/"training_metrics.json").write_text(json.dumps(log,indent=2)+"\n")
    summary={"mode":mode,"best_validation_objective":best,
             "best_epoch":min(log,key=lambda x:x["validation_objective"])["epoch"],
             "manifest_sha256":digest,"test_not_used_for_model_selection":True,
             "data_rights_self_declared_not_verified":True,
             "quality_not_verified_by_human_review":True}
    (out/"summary.json").write_text(json.dumps(summary,indent=2)+"\n")
    return summary

if __name__=="__main__":
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument("--manifest",type=Path,required=True)
    p.add_argument("--out",type=Path,required=True)
    p.add_argument("--mode",choices=["global","spatial"],required=True)
    p.add_argument("--epochs",type=int,default=30)
    p.add_argument("--batch",type=int,default=4)
    p.add_argument("--seed",type=int,default=27)
    p.add_argument("--lr",type=float,default=2e-4)
    p.add_argument("--min-train",type=int,default=50)
    p.add_argument("--min-val",type=int,default=20)
    p.add_argument("--min-test",type=int,default=20)
    a=p.parse_args()
    print(json.dumps(train(a.manifest,a.out,a.mode,a.epochs,a.batch,a.seed,
                           a.lr,a.min_train,a.min_val,a.min_test),indent=2))
