#!/usr/bin/env python3
"""Offline blind photographic A/B review: actual baseline vs trained candidate.

Inputs must be outputs from identical source frames, not synthetic reconstruction.
Creates shuffled, identical-size comparison cards, private side key, and a
reviewer voting template. Requires multiple independent human reviewers and
reports per-image consensus. NEVER approves an Android release automatically.
"""
from __future__ import annotations
import argparse
import csv
import json
import math
import secrets
from collections import defaultdict
from pathlib import Path
from PIL import Image, ImageDraw, ImageOps

def decoded(path:Path)->Image.Image:
    with Image.open(path) as f:
        return ImageOps.exif_transpose(f).convert("RGB")

def fit(img:Image.Image,w:int,h:int)->Image.Image:
    preview=img.copy()
    preview.thumbnail((w,h),Image.Resampling.LANCZOS)
    pad=Image.new("RGB",(w,h),(24,24,24))
    pad.paste(preview,((w-preview.width)//2,(h-preview.height)//2))
    return pad

def create(originals:Path,baseline:Path,candidate:Path,cards:Path,key:Path):
    if key.exists():raise FileExistsError("Never overwrite an existing blind-review key")
    files=sorted(p for p in originals.iterdir() if p.suffix.lower() in (".png",".jpg",".jpeg"))
    if not files:raise ValueError("No source photographs")
    cards.mkdir(parents=True,exist_ok=True)
    records=[]
    for source in files:
        old=baseline/(source.stem+".png")
        new=candidate/(source.stem+".png")
        if not old.is_file() or not new.is_file():
            raise FileNotFoundError("Missing matched baseline/candidate for "+source.stem)
        src=decoded(source);a=decoded(old);b=decoded(new)
        if src.size!=a.size or src.size!=b.size:
            raise ValueError("Output geometry mismatch: "+source.stem)
        challenger_left=bool(secrets.randbits(1))
        left,right=(b,a) if challenger_left else (a,b)
        sheet=Image.new("RGB",(1040,1050),(24,24,24))
        sheet.paste(fit(src,1000,395),(20,36))
        sheet.paste(fit(left,490,500),(20,483))
        sheet.paste(fit(right,490,500),(530,483))
        draw=ImageDraw.Draw(sheet)
        draw.text((20,10),"UNEDITED SOURCE",fill="white")
        draw.text((20,455),"A",fill="white")
        draw.text((530,455),"B",fill="white")
        sheet.save(cards/(source.stem+".jpg"),quality=94)
        records.append({"id":source.stem,
                        "candidate_side":"A" if challenger_left else "B"})
    key.parent.mkdir(parents=True,exist_ok=True)
    key.write_text(json.dumps(records,indent=2)+"\n",encoding="utf-8")
    with (cards/"votes_template.csv").open("w",newline="") as f:
        writer=csv.writer(f)
        writer.writerow(["id","reviewer","preference","serious_damage_side","notes"])
        for row in records:writer.writerow([row["id"],"","A/B/TIE","A/B/NONE",""])
    return len(records)

def score(key:Path,votes:Path,out:Path,min_cases:int=50,min_reviewers:int=3):
    if min_cases<1 or min_reviewers<2:raise ValueError("Review thresholds too small")
    mapping={r["id"]:r["candidate_side"] for r in json.loads(key.read_text())}
    all_votes=defaultdict(dict)
    with votes.open(newline="",encoding="utf-8-sig") as f:
        for row in csv.DictReader(f):
            ident=row["id"]
            reviewer=(row["reviewer"] or "").strip()
            preference=(row["preference"] or "").strip().upper()
            damaged=(row["serious_damage_side"] or "").strip().upper()
            if ident not in mapping or not reviewer or preference not in ("A","B","TIE") or damaged not in ("A","B","NONE"):
                raise ValueError("Unknown photo or invalid/incomplete vote")
            if reviewer in all_votes[ident]:raise ValueError("Duplicate reviewer/photo vote")
            all_votes[ident][reviewer]=(preference,damaged)
    if len(all_votes)<min_cases or any(len(v)<min_reviewers for v in all_votes.values()):
        raise ValueError("Not enough photos with independently collected reviewer votes")
    wins=losses=ties=harms=0
    for ident,reviewers in all_votes.items():
        sides=[vote[0] for vote in reviewers.values() if vote[0]!="TIE"]
        a=sides.count("A");b=sides.count("B")
        consensus="TIE" if a==b else ("A" if a>b else "B")
        if consensus=="TIE":ties+=1
        elif consensus==mapping[ident]:wins+=1
        else:losses+=1
        if any(vote[1]==mapping[ident] for vote in reviewers.values()):harms+=1
    n=wins+losses
    preference=wins/n if n else None
    # Wilson 95% interval for case-level wins, not pseudo-independent votes.
    z=1.96
    if n:
        denom=1+z*z/n
        center=(preference+z*z/(2*n))/denom
        radius=z*math.sqrt(preference*(1-preference)/n+z*z/(4*n*n))/denom
        interval=[max(0,center-radius),min(1,center+radius)]
    else:interval=None
    report={"source_photos":len(all_votes),"reviewers_min":min(len(x) for x in all_votes.values()),
        "candidate_preferred_cases":wins,"baseline_preferred_cases":losses,
        "ties":ties,"candidate_preference_decisive":preference,
        "wilson_95pct":interval,"candidate_serious_damage_cases":harms,
        "no_automatic_release_approval":True,
        "quality_requires_manual_independent_review":True}
    out.parent.mkdir(parents=True,exist_ok=True)
    out.write_text(json.dumps(report,indent=2)+"\n")
    return report

if __name__=="__main__":
    p=argparse.ArgumentParser()
    sub=p.add_subparsers(dest="action",required=True)
    a=sub.add_parser("cards")
    for name in ("originals","baseline","candidate","cards","key"):
        a.add_argument("--"+name,type=Path,required=True)
    b=sub.add_parser("score")
    for name in ("key","votes","out"):b.add_argument("--"+name,type=Path,required=True)
    b.add_argument("--min-cases",type=int,default=50)
    b.add_argument("--min-reviewers",type=int,default=3)
    opts=p.parse_args()
    if opts.action=="cards":result={"cards":create(opts.originals,opts.baseline,opts.candidate,opts.cards,opts.key)}
    else:result=score(opts.key,opts.votes,opts.out,opts.min_cases,opts.min_reviewers)
    print(json.dumps(result,indent=2))
