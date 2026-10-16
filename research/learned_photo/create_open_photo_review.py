#!/usr/bin/env python3
"""Compare actual source, V9 Android export, and guarded learned Android export.

Use ONLY the six fixed public/CC0 fixture names. Reject unexpected files and
private licensed FiveK source; models and licensed images are NEVER uploaded.
"""
import argparse,csv,json
from pathlib import Path
from PIL import Image,ImageOps,ImageDraw
NAMES=("astronaut","coffee","chelsea","rocket","camera","clock")
WIDTH=420
HEIGHT=470

def display(im:Image.Image)->Image.Image:
    p=ImageOps.exif_transpose(im).convert("RGB")
    p.thumbnail((WIDTH,HEIGHT),Image.Resampling.LANCZOS)
    out=Image.new("RGB",(WIDTH,HEIGHT),(28,31,34))
    out.paste(p,((WIDTH-p.width)//2,(HEIGHT-p.height)//2))
    return out

def build(inputs:Path,out:Path):
    records=list(csv.DictReader((inputs/"decisions.csv").open(newline="")))
    assert len(records)==len(NAMES) and {r["id"] for r in records}==set(NAMES)
    out.mkdir(parents=True,exist_ok=True)
    summary=[]
    for name in NAMES:
        row=next(row for row in records if row["id"]==name)
        imgs=[]
        dims=[]
        for suffix in ("source","v9","challenger"):
            with Image.open(inputs/(name+"_"+suffix+".png")) as image:
                dims.append(image.size)
                imgs.append(display(image))
        assert len(set(dims))==1,f"Image geometry mismatch: {name}"
        sheet=Image.new("RGB",(3*WIDTH+32,HEIGHT+84),(20,22,26))
        draw=ImageDraw.Draw(sheet)
        draw.text((12,12),name.upper().replace("_"," ")+
          "  |  learned accepted: "+row["learned_accepted"]+
          "  |  learned strength: "+row["strength"],fill=(242,242,241))
        for index,(caption,img) in enumerate(zip(("SOURCE","V9.3","GUARDED LEARNED"),imgs)):
            x=8+index*WIDTH
            sheet.paste(img,(x,56))
            draw.text((x+8,56+HEIGHT+9),caption,fill=(210,210,213))
        sheet.save(out/(name+"_comparison.jpg"),quality=92,optimize=True)
        summary.append({k:row[k] for k in ("id","mode","learned_accepted","strength","mean_rgb_change")})
    (out/"public_photo_review_info.json").write_text(json.dumps({
        "images":summary,
        "images_public_fixture_only":True,
        "selected_research_model_not_distribution_cleared":True,
        "review_purpose":"Compare photographic appearance only; not a release",
        "comparison_uses_actual_android_v9_and_trained_onnx_execution":True
    },indent=2)+"\n")
    print(json.dumps({"comparisons":len(summary),"output":str(out)}))

if __name__=="__main__":
    a=argparse.ArgumentParser()
    a.add_argument("--inputs",type=Path,required=True)
    a.add_argument("--out",type=Path,required=True)
    b=a.parse_args()
    build(b.inputs,b.out)
