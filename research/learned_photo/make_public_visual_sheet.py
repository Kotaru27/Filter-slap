#!/usr/bin/env python3
"""Public-domain photo collage: ORIGINAL / actual V9 / learned correction.

Only the six public fixtures leave the temporary CI runner. Trained weights,
FiveK original/expert photo pairs, and holdout outputs are NEVER uploaded.
"""
import argparse
import csv
from pathlib import Path
from PIL import Image,ImageOps,ImageDraw

def fit(path,w,h):
    with Image.open(path) as f:
        im=ImageOps.exif_transpose(f).convert("RGB")
    im.thumbnail((w,h),Image.Resampling.LANCZOS)
    canvas=Image.new("RGB",(w,h),(236,236,236))
    canvas.paste(im,((w-im.width)//2,(h-im.height)//2))
    return canvas

def main():
    p=argparse.ArgumentParser()
    p.add_argument("--input",type=Path,required=True)
    p.add_argument("--output",type=Path,required=True)
    a=p.parse_args()
    with (a.input/"manifest.csv").open(newline="") as f:
        rows=list(csv.DictReader(f))
    if len(rows)!=6 or {r["photo"] for r in rows}!={"astronaut","coffee","chelsea","rocket","camera","clock"}:
        raise ValueError("Expected six public-domain photos only")
    # Max dimensions limited; output is a review image, not evaluation pixels.
    w,h=295,290
    top=55;gap=12;caption=25
    sheet=Image.new("RGB",(gap+3*(w+gap),top+len(rows)*(h+caption+gap)+gap),"white")
    draw=ImageDraw.Draw(sheet)
    draw.text((gap,13),"PUBLIC-DOMAIN PHOTOGRAPHS | ORIGINAL / REAL V9 / LEARNED",fill="black")
    for i,row in enumerate(rows):
        name=row["photo"]
        y=top+i*(h+caption+gap)
        for col,variant in enumerate(("original","v9","guarded")):
            file=a.input/(name+"-"+variant+".png")
            if not file.exists():raise ValueError("Missing rendered comparison "+str(file))
            x=gap+col*(w+gap)
            sheet.paste(fit(file,w,h),(x,y))
            label=(name+": "+variant)
            if variant=="guarded":label+=(" "+row["decision"]+" @"+row["strength"])
            draw.text((x,y+h+2),label,fill="black")
    a.output.parent.mkdir(parents=True,exist_ok=True)
    sheet.save(a.output,format="JPEG",quality=91,optimize=True)
    print("VISUAL_SHEET_FILE",a.output,flush=True)

if __name__=="__main__":main()
