#!/usr/bin/env python3
"""Public-domain research visualization assets only; never model training."""
from pathlib import Path
import argparse
from PIL import Image
from skimage import data
FIXTURES = (
    ("astronaut", data.astronaut),  # NASA
    ("coffee", data.coffee),        # photographer donated CC0
    ("chelsea", data.chelsea),      # CC0
    ("rocket", data.rocket),        # SpaceX public image
    ("camera", data.camera),        # CC0
    ("clock", data.clock),          # public domain
)
def main():
    p=argparse.ArgumentParser()
    p.add_argument("--output-dir",type=Path,required=True)
    a=p.parse_args()
    a.output_dir.mkdir(parents=True,exist_ok=True)
    for name,fn in FIXTURES:
        Image.fromarray(fn()).convert("RGB").save(a.output_dir/(name+".png"))
        print("PUBLIC_REVIEW_SOURCE",name,flush=True)
if __name__=="__main__":main()
