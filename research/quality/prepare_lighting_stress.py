#!/usr/bin/env python3
"""Research-only image-illumination stress suite.

Create two nonuniform lighting variants from *multiple* public photo sources.
No fixture, image name, region, gradient or target enters Android production.
Synthetic lighting is diagnostic and cannot substitute for held-out photographs.
"""
from pathlib import Path
from PIL import Image
import numpy as np
import argparse

def smoothstep(x):
    x=np.clip(x,0.0,1.0)
    return x*x*(3-2*x)

def main():
    a=argparse.ArgumentParser()
    a.add_argument("--input-dir",required=True)
    a.add_argument("--reference-dir",required=True)
    args=a.parse_args()
    source=Path(args.input_dir)
    reference=Path(args.reference_dir)
    reference.mkdir(parents=True,exist_ok=True)
    originals=sorted(source.glob("*.png"))
    if len(originals)<4:
        raise RuntimeError("Expected multiple public photographic sources")
    # Diverse images and both spatial illumination directions. These parameters
    # generate a perturbation, not a desired editing profile or hardcoded fix.
    for file in originals[:4]:
        with Image.open(file) as img:
            rgb=np.asarray(img.convert("RGB"),dtype=np.float32)
            h,w=rgb.shape[:2]
            yy=np.linspace(0.0,1.0,h,dtype=np.float32)[:,None,None]
            xx=np.linspace(0.0,1.0,w,dtype=np.float32)[None,:,None]
            for orientation in ("top_shadow","side_shadow"):
                progression=(yy if orientation=="top_shadow" else xx)
                illumination=0.22+0.78*smoothstep((progression-.26)/.52)
                result=np.clip(rgb*illumination,0,255).astype(np.uint8)
                out=f"stress_{orientation}_{file.stem}.png"
                Image.fromarray(result).save(source/out,optimize=True)
                # The unchanged public photo is a photometric reference, NOT
                # an expert retouch or an artistic truth.
                Image.fromarray(rgb.astype(np.uint8)).save(reference/out,optimize=True)
                print("LIGHTING_STRESS",out,"shape",w,h)
if __name__=="__main__":
    main()
