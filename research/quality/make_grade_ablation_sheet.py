#!/usr/bin/env python3
"""Research-only: inspect original/V9-selected/learned-grade outputs together."""
from pathlib import Path
from PIL import Image,ImageDraw
import argparse,base64

def fit(im,w,h):
    res=im.copy().convert("RGB")
    res.thumbnail((w,h),Image.Resampling.LANCZOS)
    b=Image.new("RGB",(w,h),"#e5e5e5")
    b.paste(res,((w-res.width)//2,(h-res.height)//2))
    return b

def main():
    p=argparse.ArgumentParser()
    p.add_argument("--directory",required=True)
    p.add_argument("--output",required=True)
    args=p.parse_args()
    root=Path(args.directory)
    records=sorted(root.glob("*-original.png"))
    w,h=235,175
    margin=8;label=25
    out=Image.new("RGB",(margin+4*(w+margin),40+len(records)*(h+label+margin)),"white")
    draw=ImageDraw.Draw(out)
    draw.text((margin,8),"Pretrained LUT study: original | V9 | learned soft | learned strong",fill="black")
    for row,p in enumerate(records):
        name=p.name.removesuffix("-original.png")
        base=root/"learned-grade-study"/name
        paths=[p,root/f"{name}-edited.png",base/"learned-soft.png",base/"learned-strong.png"]
        names=["ORIGINAL","V9","LUT SOFT","LUT STRONG"]
        y=40+row*(h+label+margin)
        for col,src in enumerate(paths):
            x=margin+col*(w+margin)
            if src.exists():
                with Image.open(src) as im:
                    out.paste(fit(im,w,h),(x,y))
            else:
                draw.rectangle([x,y,x+w,y+h],fill="#cccccc")
                draw.text((x+10,y+h//2),"REJECTED / MISSING",fill="black")
            draw.text((x,y+h+1),f"{name}: {names[col]}",fill="black")
    target=Path(args.output)
    target.parent.mkdir(parents=True,exist_ok=True)
    out.save(target,format="JPEG",quality=74,optimize=True)
    print("GRADE_SHEET_BYTES",target.stat().st_size)
    s=base64.b64encode(target.read_bytes()).decode("ascii")
    for i in range(0,len(s),800):
        print("GRADE_SHEET_CHUNK "+s[i:i+800])
    print("GRADE_SHEET_END")

if __name__=="__main__":
    main()
