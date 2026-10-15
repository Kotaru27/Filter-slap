"""Full-resolution research rendering, blinded A/B cards, independent votes.

Pixel-distance measurements are diagnostic and never approve a release.
Baseline must be the REAL Android V9/V9.3 exported photograph.
"""
import argparse
import csv
import json
import secrets
from pathlib import Path
import numpy as np
from PIL import Image, ImageDraw
import torch
try:
    from .data import read_manifest, srgb
    from .model import LearnedPhotoEditor
except ImportError:
    from data import read_manifest, srgb
    from model import LearnedPhotoEditor

def load(checkpoint:Path,device:str="cpu")->LearnedPhotoEditor:
    obj=torch.load(checkpoint,map_location=device,weights_only=True)
    if obj.get("format")!="filter-slap-learned-v1" or obj.get("mode") not in {"global","spatial"}:
        raise ValueError("invalid or unknown research model")
    model=LearnedPhotoEditor(mode=obj["mode"])
    model.load_state_dict(obj["model"],strict=True)
    return model.to(device).eval()

def tensor(img:Image.Image,device:str):
    array=np.asarray(img,np.float32).copy().transpose(2,0,1)/255.
    return torch.from_numpy(array).unsqueeze(0).to(device)

def render(model:LearnedPhotoEditor,image:Image.Image,tile_rows:int=256)->Image.Image:
    if tile_rows<1:raise ValueError("tile_rows must be positive")
    device=next(model.parameters()).device
    with torch.inference_mode():
        # Model predicts once from whole photo; full-resolution rendering uses
        # global (not per-tile) x/y coordinates to avoid seams.
        context=image.resize((256,256),Image.Resampling.LANCZOS)
        grid=model.coefficients(tensor(context,device.type))
        out=Image.new("RGB",image.size)
        for top in range(0,image.height,tile_rows):
            strip=image.crop((0,top,image.width,min(top+tile_rows,image.height)))
            actual=model._render_strip(tensor(strip,device.type),grid,top,image.height)
            encoded=np.rint(actual.squeeze(0).permute(1,2,0).cpu().numpy()*255.)
            out.paste(Image.fromarray(encoded.clip(0,255).astype(np.uint8),"RGB"),(0,top))
    return out

def l1(a:Image.Image,b:Image.Image)->float:
    if a.size!=b.size:raise ValueError("geometry mismatch")
    return float(np.abs(np.asarray(a,np.float32)-np.asarray(b,np.float32)).mean()/255.)

def evaluate(manifest:Path,checkpoint:Path,out:Path,split:str="val",
             min_test:int=20)->dict:
    if split not in {"val","test"}:raise ValueError("invalid evaluation split")
    rows=read_manifest(manifest,1,1,min_test)
    model=load(checkpoint)
    out.mkdir(parents=True,exist_ok=True)
    measurements=[]
    for row in rows:
        if row.split!=split:continue
        original=srgb(row.source)
        target=srgb(row.target)
        if original.size!=target.size:
            raise ValueError("expert pair not co-registered")
        predicted=render(model,original)
        predicted.save(out/(row.ident+".png"))
        measurements.append({"id":row.ident,"category":row.category,
          "preserve":row.preserve,
          "model_l1_to_expert":l1(predicted,target),
          "unedited_l1_to_expert":l1(original,target),
          "edit_l1":l1(original,predicted)})
    if not measurements:raise ValueError("no evaluation photos")
    with (out/"measurements.csv").open("w",newline="") as stream:
        writer=csv.DictWriter(stream,fieldnames=list(measurements[0]))
        writer.writeheader()
        writer.writerows(measurements)
    summary={"count":len(measurements),"split":split,"mode":model.mode,
             "mean_model_l1_to_expert":float(np.mean([x["model_l1_to_expert"] for x in measurements])),
             "mean_unedited_l1_to_expert":float(np.mean([x["unedited_l1_to_expert"] for x in measurements])),
             "human_preference_not_measured":True,"release_authorized":False}
    (out/"evaluation.json").write_text(json.dumps(summary,indent=2)+"\n")
    return summary

def panel(img:Image.Image,w:int,h:int)->Image.Image:
    p=img.copy()
    p.thumbnail((w,h),Image.Resampling.LANCZOS)
    canvas=Image.new("RGB",(w,h),(32,32,32))
    canvas.paste(p,((w-p.width)//2,(h-p.height)//2))
    return canvas

def blind_cards(manifest:Path,challenger:Path,baseline:Path,
                cards:Path,key_path:Path,min_test:int=20)->int:
    rows=read_manifest(manifest,1,1,min_test)
    if key_path.exists():raise FileExistsError("cannot overwrite secret comparison key")
    cards.mkdir(parents=True,exist_ok=True)
    secrets_key=[]
    for row in rows:
        if row.split!="test":continue
        original=srgb(row.source)
        attempt=srgb(challenger/(row.ident+".png"))
        control=srgb(baseline/(row.ident+".png"))
        if not(original.size==attempt.size==control.size):
            raise ValueError("mismatched image resolution")
        side="A" if secrets.randbits(1) else "B"
        left=attempt if side=="A" else control
        right=control if side=="A" else attempt
        card=Image.new("RGB",(960,720),(23,23,23))
        card.paste(panel(original,920,230),(20,28))
        card.paste(panel(left,450,320),(20,305))
        card.paste(panel(right,450,320),(490,305))
        draw=ImageDraw.Draw(card)
        draw.text((20,5),"Original: "+row.ident,fill="white")
        draw.text((20,283),"A",fill="white")
        draw.text((490,283),"B",fill="white")
        card.save(cards/(row.ident+".jpg"),quality=92)
        secrets_key.append({"id":row.ident,"challenger_side":side,
                            "category":row.category,"preserve":row.preserve})
    key_path.parent.mkdir(parents=True,exist_ok=True)
    key_path.write_text(json.dumps(secrets_key,indent=2)+"\n")
    with (cards/"votes_template.csv").open("w",newline="") as stream:
        writer=csv.writer(stream)
        writer.writerow(["id","reviewer","preference","serious_harm_side","notes"])
        for row in secrets_key:writer.writerow([row["id"],"","A/B/TIE","A/B/NONE",""])
    return len(secrets_key)

def score_votes(key:Path,votes:Path,out:Path,min_cases:int=20,
                min_reviewers:int=3)->dict:
    lookup={x["id"]:x for x in json.loads(key.read_text())}
    seen=set();reviewers=set();voted=[]
    with votes.open(newline="",encoding="utf-8-sig") as stream:
        for row in csv.DictReader(stream):
            ident=row["id"]
            if ident not in lookup:raise ValueError("unknown review case")
            reviewer=row["reviewer"].strip()
            choice=row["preference"].upper().strip()
            harm=row["serious_harm_side"].upper().strip()
            if not reviewer or choice not in {"A","B","TIE"} or harm not in {"A","B","NONE"}:
                raise ValueError("invalid/incomplete reviewer vote")
            if (ident,reviewer) in seen:raise ValueError("duplicate reviewer vote")
            seen.add((ident,reviewer));reviewers.add(reviewer)
            voted.append((ident,choice,harm))
    if len({x[0] for x in voted})<min_cases or len(reviewers)<min_reviewers:
        raise ValueError("insufficient independent human review")
    decisive=[(i,c) for i,c,h in voted if c!="TIE"]
    wins=sum(c==lookup[i]["challenger_side"] for i,c in decisive)
    harms=sum(h==lookup[i]["challenger_side"] for i,c,h in voted)
    by_category={}
    for category in sorted({x["category"] for x in lookup.values()}):
        choices=[(i,c) for i,c in decisive if lookup[i]["category"]==category]
        by_category[category]={"decisive":len(choices),
             "challenger_wins":sum(c==lookup[i]["challenger_side"] for i,c in choices)}
    result={"reviewers":len(reviewers),"cases":len({x[0] for x in voted}),
            "votes":len(voted),"decisive_votes":len(decisive),
            "challenger_wins":wins,"challenger_preference":
                wins/len(decisive) if decisive else None,
            "challenger_marked_serious_harm":harms,"by_category":by_category,
            "automatic_release_approval":False}
    out.parent.mkdir(parents=True,exist_ok=True)
    out.write_text(json.dumps(result,indent=2)+"\n")
    return result

if __name__=="__main__":
    p=argparse.ArgumentParser(description=__doc__)
    sub=p.add_subparsers(dest="cmd",required=True)
    a=sub.add_parser("render")
    a.add_argument("--manifest",type=Path,required=True)
    a.add_argument("--checkpoint",type=Path,required=True)
    a.add_argument("--out",type=Path,required=True)
    a.add_argument("--split",choices=["val","test"],default="val")
    a.add_argument("--min-test",type=int,default=20)
    b=sub.add_parser("blind")
    b.add_argument("--manifest",type=Path,required=True)
    b.add_argument("--challenger",type=Path,required=True)
    b.add_argument("--baseline",type=Path,required=True)
    b.add_argument("--cards",type=Path,required=True)
    b.add_argument("--key",type=Path,required=True)
    b.add_argument("--min-test",type=int,default=20)
    c=sub.add_parser("score")
    c.add_argument("--key",type=Path,required=True)
    c.add_argument("--votes",type=Path,required=True)
    c.add_argument("--out",type=Path,required=True)
    c.add_argument("--min-cases",type=int,default=20)
    c.add_argument("--min-reviewers",type=int,default=3)
    args=p.parse_args()
    if args.cmd=="render": result=evaluate(args.manifest,args.checkpoint,args.out,args.split,args.min_test)
    elif args.cmd=="blind":result={"cards":blind_cards(args.manifest,args.challenger,args.baseline,args.cards,args.key,args.min_test)}
    else:result=score_votes(args.key,args.votes,args.out,args.min_cases,args.min_reviewers)
    print(json.dumps(result,indent=2))
