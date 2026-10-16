#!/usr/bin/env python3
"""Produce only the six previously approved open-image CI fixtures.

Sources and provenance align with research/quality/prepare_public_photos.py
in the V9.3 research branch. NEVER read private FiveK images from this tool.
"""
import argparse
from pathlib import Path
from PIL import Image
from skimage import data

PHOTOS={
 "astronaut": data.astronaut,
 "coffee": data.coffee,
 "chelsea": data.chelsea,
 "rocket": data.rocket,
 "camera": data.camera,
 "clock": data.clock,
}
def main():
    parser=argparse.ArgumentParser()
    parser.add_argument("--out",required=True,type=Path)
    root=parser.parse_args().out
    root.mkdir(parents=True,exist_ok=True)
    for name,loader in PHOTOS.items():
        Image.fromarray(loader()).convert("RGB").save(root/(name+".png"))
    assert len(list(root.glob("*.png")))==len(PHOTOS)
    print("Prepared six public-domain/CC0 evaluation fixtures; not research-training photos.")
if __name__=="__main__":main()
