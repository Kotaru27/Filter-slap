"""Reproducible, local-only V5 vs learned-model photographic benchmark.

CLI commands:
    validate --manifest ...
    render --manifest ... --model-dir ... --out ...
    blind --manifest ... --learned-dir ... --out ... --key ...
    score --key ... --votes ... --out ...

WARNING: No aesthetic-quality claims come from metric proxies or model inference.
Only pre-registered, blind human preference can support photographic wins.
All photos remain local. We never upload them to any service.
"""
from __future__ import annotations
import argparse
import csv
import hashlib
import json
from collections import Counter
from pathlib import Path
import secrets
from typing import NamedTuple

import numpy as np
from PIL import Image, ImageDraw

try:
    from .adaptive_lut import AdaptiveLUT,sha256,to_srgb
except ImportError:
    from adaptive_lut import AdaptiveLUT,sha256,to_srgb

FIELDS=("id","category","original","v5","rights","split","group")
VALID_SPLITS={"test","validation","training"}


class Item(NamedTuple):
    ident: str
    category: str
    original: Path
    v5: Path
    rights: str
    split: str
    group: str


def load_manifest(path: Path, *, require_v5: bool = False, min_test: int=0) -> list[Item]:
    items=[]
    with path.open(newline="",encoding="utf-8-sig") as stream:
        reader=csv.DictReader(stream)
        if not set(FIELDS).issubset(reader.fieldnames or []):
            raise ValueError(f"Manifest missing fields {set(FIELDS)-set(reader.fieldnames or [])}")
        for n,row in enumerate(reader,2):
            key=(row["id"] or "").strip()
            if not key or not all(ch.isalnum() or ch in "_-" for ch in key):
                raise ValueError(f"Line {n}: invalid ID (letters/numbers/underscore/hyphen only)")
            split=row["split"].strip().lower()
            if split not in VALID_SPLITS:raise ValueError(f"Line {n}: invalid split {split!r}")
            if not row["rights"].strip() or row["rights"].strip().lower() in ("unknown","none","unverified"):
                raise ValueError(f"Line {n}: explicit lawful image usage rights required")
            original=(path.parent/row["original"]).resolve()
            v5=(path.parent/row["v5"]).resolve() if row["v5"].strip() else Path("")
            if not original.is_file():raise FileNotFoundError(f"Line {n}: {original}")
            if require_v5 and not v5.is_file():raise FileNotFoundError(f"Line {n}: actual V5 output missing for {key}")
            group=row["group"].strip() or key
            items.append(Item(key,row["category"].strip(),original,v5,row["rights"].strip(),split,group))
    if len(set(x.ident for x in items))!=len(items):raise ValueError("Duplicate IDs")
    if len(set(x.original for x in items))!=len(items):raise ValueError("Duplicate original path")
    assignments={}
    for x in items:
        if x.group in assignments and assignments[x.group]!=x.split:
            raise ValueError(f"Group leakage across train/validation/test: {x.group}")
        assignments[x.group]=x.split
    if len([x for x in items if x.split=="test"])<min_test:
        raise ValueError(f"Only {sum(x.split=='test' for x in items)} test images; require {min_test}")
    return items


def load_image(path:Path)->Image.Image:
    with Image.open(path) as img:
        return to_srgb(img)


def stats(original:Image.Image,enhanced:Image.Image)->dict:
    # Descriptive pixel statistics are diagnostic, NOT subjective quality.
    if original.size!=enhanced.size:
        raise ValueError(f"Image geometry mismatch {original.size} != {enhanced.size}")
    a=np.asarray(original,dtype=np.float32)/255.
    b=np.asarray(enhanced,dtype=np.float32)/255.
    luma_a=np.sum(a*np.array([.2126,.7152,.0722],np.float32),axis=-1)
    luma_b=np.sum(b*np.array([.2126,.7152,.0722],np.float32),axis=-1)
    s_a=a.max(axis=-1)-a.min(axis=-1)
    s_b=b.max(axis=-1)-b.min(axis=-1)
    changed=np.max(np.abs(a-b),axis=-1)>.03
    return {"luminance_mean_delta":round(float(luma_b.mean()-luma_a.mean()),6),
            "chroma_range_delta":round(float(s_b.mean()-s_a.mean()),6),
            "changed_pixel_fraction":round(float(changed.mean()),6),
            "near_black_fraction_delta":round(float((luma_b<.01).mean()-(luma_a<.01).mean()),6),
            "near_white_fraction_delta":round(float((luma_b>.99).mean()-(luma_a>.99).mean()),6),
            "mae":round(float(np.mean(np.abs(a-b))),6)}


def validate(args):
    items=load_manifest(args.manifest,require_v5=args.require_v5,min_test=args.min_test)
    cats=Counter(x.category for x in items if x.split=="test")
    out={"count":len(items),"test_count":sum(x.split=="test" for x in items),"test_categories":cats,"valid":True}
    print(json.dumps(out,indent=2))


def render(args):
    items=load_manifest(args.manifest,min_test=args.min_test)
    if args.split not in VALID_SPLITS:raise ValueError("Use one explicit dataset split")
    engine=AdaptiveLUT(args.model_dir)
    args.out.mkdir(parents=True,exist_ok=True)
    records=[]
    for item in items:
        if item.split!=args.split:continue
        original=load_image(item.original)
        inferred=engine.enhance(original)
        destination=args.out/(item.ident+".png")
        inferred.output.save(destination,format="PNG")
        records.append({"id":item.ident,"category":item.category,"split":item.split,
                        "source_sha256":sha256(item.original),"learned_sha256":sha256(destination),
                        "model_luts_sha256":inferred.model_sha256[0],"model_classifier_sha256":inferred.model_sha256[1],
                        "coefficients":inferred.coefficients,"out_of_range_fraction":inferred.out_of_range_fraction,
                        "elapsed_ms_CPU":round(inferred.elapsed_ms,2),"diagnostic_not_quality":stats(original,inferred.output)})
        print(f"Rendered {item.ident}: coefficients={inferred.coefficients}")
    (args.out/"inference_audit.json").write_text(json.dumps(records,indent=2),encoding="utf-8")
    print(f"Rendered {len(records)} {args.split} images. Performance is HOST CPU, not Android.")


def _panel(img:Image.Image,width:int=540,height:int=365)->Image.Image:
    picture=img.copy()
    picture.thumbnail((width,height),Image.Resampling.LANCZOS)
    area=Image.new("RGB",(width,height),(32,32,32))
    area.paste(picture,((width-picture.width)//2,(height-picture.height)//2))
    return area


def blind(args):
    rows=load_manifest(args.manifest,require_v5=(args.control=="v5"),min_test=args.min_test)
    test=[x for x in rows if x.split=="test"]
    if not test:raise ValueError("No test split")
    args.out.mkdir(parents=True,exist_ok=True)
    if args.key.exists():raise FileExistsError("Refusing to overwrite secret answer key")
    keys=[]
    for item in test:
        source=load_image(item.original)
        existing=load_image(item.v5) if args.control=="v5" else source
        candidate=load_image(args.learned_dir/(item.ident+".png"))
        if existing.size!=source.size or candidate.size!=source.size:
            raise ValueError(f"Resolutions not aligned for {item.ident}")
        # secrets.SystemRandom rather than a hardcoded random seed prevents simple
        # deducing of candidate identity from published materials.
        learned_side="A" if secrets.randbits(1) else "B"
        a=candidate if learned_side=="A" else existing
        b=existing if learned_side=="A" else candidate
        page=Image.new("RGB",(1120,830),(22,22,22))
        drawer=ImageDraw.Draw(page)
        page.paste(_panel(source, 1080,290),(20,35))
        page.paste(_panel(a,540,385),(15,370))
        page.paste(_panel(b,540,385),(565,370))
        drawer.text((20,10),f"Original | case {item.ident}",fill="white")
        drawer.text((20,342),"A",fill="white")
        drawer.text((570,342),"B",fill="white")
        page.save(args.out/(item.ident+".jpg"),quality=91)
        keys.append({"id":item.ident,"category":item.category,"learned_side":learned_side,
                     "source_sha256":sha256(item.original),"control_type":args.control,"control_sha256":sha256(item.v5) if args.control=="v5" else sha256(item.original),
                     "learned_sha256":sha256(args.learned_dir/(item.ident+".png"))})
    # Key should live outside published reviewer directory. No key in cards.
    args.key.parent.mkdir(parents=True,exist_ok=True)
    args.key.write_text(json.dumps(keys,indent=2),encoding="utf-8")
    with (args.out/"votes_TEMPLATE.csv").open("w",newline="") as f:
        writer=csv.writer(f);writer.writerow(["id","reviewer","choice","harm_side","notes"])
        for x in test:writer.writerow([x.ident,"reviewer-id","A|B|TIE","A|B|NONE",""])
    print(f"Created {len(keys)} blinded A/B cards. KEEP {args.key} PRIVATE from reviewers.")


def score(args):
    keys=json.loads(args.key.read_text(encoding="utf-8"))
    by_id={x["id"]:x for x in keys}
    votes=[]
    with args.votes.open(newline="",encoding="utf-8-sig") as f:
        for row in csv.DictReader(f):
            if row["id"] not in by_id:raise ValueError(f"Unknown case {row['id']}")
            if row["choice"].upper() not in ("A","B","TIE"):raise ValueError(f"Invalid vote {row['choice']}")
            if row["harm_side"].upper() not in ("A","B","NONE"):raise ValueError("harm_side must be A, B or NONE")
            votes.append({**row,"choice":row["choice"].upper(),"learned_preferred":
                None if row["choice"].upper()=="TIE" else row["choice"].upper()==by_id[row["id"]]["learned_side"]})
    if len(set((x["id"],x["reviewer"]) for x in votes))!=len(votes):
        raise ValueError("Duplicate reviewer votes")
    decisive=[v for v in votes if v["learned_preferred"] is not None]
    wins=sum(v["learned_preferred"] for v in decisive)
    results={"votes":len(votes),"reviewers":len(set(v['reviewer'] for v in votes)),
             "tested_cases":len(set(v['id'] for v in votes)),"all_test_cases":len(keys),
             "decisive_votes":len(decisive),"learned_wins":wins,"v5_wins":len(decisive)-wins,
             "ties":len(votes)-len(decisive),"learned_preference_decisive":wins/len(decisive) if decisive else None,
             "learned_marked_harmful_votes":sum(x["harm_side"].upper()==by_id[x["id"]]["learned_side"] for x in votes),
             "control_type":sorted(set(x["control_type"] for x in keys)),
             "by_category":{}}
    for c in sorted(set(by_id[v['id']]['category'] for v in votes)):
        group=[v for v in decisive if by_id[v['id']]['category']==c]
        results["by_category"][c]={"decisive":len(group),"learned_wins":sum(x['learned_preferred'] for x in group)}
    args.out.parent.mkdir(parents=True,exist_ok=True)
    args.out.write_text(json.dumps(results,indent=2),encoding="utf-8")
    print(json.dumps(results,indent=2))
    print("NOTE: Preference does not establish non-regression on already-good images without a separate no-edit baseline.")


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    subs=parser.add_subparsers(dest="cmd",required=True)
    a=subs.add_parser("validate")
    a.add_argument("--manifest",type=Path,required=True)
    a.add_argument("--min-test",type=int,default=200)
    a.add_argument("--require-v5",action="store_true")
    a.set_defaults(run=validate)
    a=subs.add_parser("render")
    a.add_argument("--manifest",type=Path,required=True)
    a.add_argument("--model-dir",type=Path,required=True)
    a.add_argument("--out",type=Path,required=True)
    a.add_argument("--split",choices=sorted(VALID_SPLITS),default="test")
    a.add_argument("--min-test",type=int,default=200)
    a.set_defaults(run=render)
    a=subs.add_parser("blind")
    a.add_argument("--manifest",type=Path,required=True)
    a.add_argument("--learned-dir",type=Path,required=True)
    a.add_argument("--out",type=Path,required=True)
    a.add_argument("--key",type=Path,required=True)
    a.add_argument("--control",choices=["v5","original"],default="v5",help="Blind challenger against real V5 saved output, or no-edit original")
    a.add_argument("--min-test",type=int,default=200)
    a.set_defaults(run=blind)
    a=subs.add_parser("score")
    a.add_argument("--key",type=Path,required=True)
    a.add_argument("--votes",type=Path,required=True)
    a.add_argument("--out",type=Path,required=True)
    a.set_defaults(run=score)
    args=parser.parse_args()
    args.run(args)

if __name__=="__main__":main()
