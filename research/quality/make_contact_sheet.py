#!/usr/bin/env python3
"""Human-review collage for public-photo comparison outputs.
Never used by the Android renderer or candidate selector.
Emits small JPEG as log chunks to permit independent inspection.
"""
from pathlib import Path
import argparse
import base64
import io
from PIL import Image, ImageDraw

def contain(im, box_w, box_h):
    resized = im.copy()
    resized.thumbnail((box_w,box_h), Image.Resampling.LANCZOS)
    output = Image.new("RGB", (box_w,box_h), "#ececec")
    output.paste(resized, ((box_w-resized.width)//2,(box_h-resized.height)//2))
    return output

def main():
    parser=argparse.ArgumentParser()
    parser.add_argument("--directory",required=True)
    parser.add_argument("--output",required=True)
    parser.add_argument("--label",required=True)
    args=parser.parse_args()
    root=Path(args.directory)
    before=sorted(root.glob("*-original.png"))
    if not before:
        print("PHOTO_CONTACT_SHEET_ABSENT")
        return
    image_w,image_h=310,260
    gap=12;head=36;label_h=22
    width=gap+2*(image_w+gap)
    height=head+len(before)*(image_h+label_h+gap)+gap
    canvas=Image.new("RGB",(width,height),"white")
    draw=ImageDraw.Draw(canvas)
    draw.text((gap,10),f"PUBLIC REFERENCE   ORIGINAL / {args.label}",fill="black")
    for i,p in enumerate(before):
        name=p.name.removesuffix("-original.png")
        mate=root/f"{name}-edited.png"
        if not mate.exists():
            raise FileNotFoundError(mate)
        y=head+i*(image_h+label_h+gap)
        with Image.open(p) as source, Image.open(mate) as edited:
            canvas.paste(contain(source.convert("RGB"),image_w,image_h),(gap,y))
            canvas.paste(contain(edited.convert("RGB"),image_w,image_h),(gap*2+image_w,y))
        draw.text((gap,y+image_h+2),f"{name}: original",fill="black")
        draw.text((gap*2+image_w,y+image_h+2),f"{name}: {args.label}",fill="black")
    output=Path(args.output)
    output.parent.mkdir(parents=True,exist_ok=True)
    canvas.save(output,format="JPEG",quality=74,optimize=True)
    raw=output.read_bytes()
    print(f"PHOTO_CONTACT_SHEET_BYTES {len(raw)}")
    encoded=base64.b64encode(raw).decode("ascii")
    for n in range(0,len(encoded),800):
        print("PHOTO_SHEET_CHUNK "+encoded[n:n+800])
    print("PHOTO_SHEET_END")

if __name__=="__main__":
    main()
