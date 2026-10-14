#!/usr/bin/env python3
"""Download-free public-domain / CC0 photographic fixtures for CI.
Only evaluation fixtures. No image category or colors are used by app code.
References: https://scikit-image.org/docs/stable/api/skimage.data
"""
from pathlib import Path
import argparse
from PIL import Image
from skimage import data

FIXTURES = (
    ("astronaut", data.astronaut),  # NASA: public domain
    ("coffee", data.coffee),        # Rachel Michetti: CC0
    ("chelsea", data.chelsea),      # Stefan van der Walt: CC0
    ("rocket", data.rocket),        # SpaceX: public domain
    ("camera", data.camera),        # Lav Varshney: CC0
    ("clock", data.clock),          # Stefan van der Walt: public domain
)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--output-dir", required=True)
    args = parser.parse_args()
    destination = Path(args.output_dir)
    destination.mkdir(parents=True, exist_ok=True)
    for name, loader in FIXTURES:
        image = Image.fromarray(loader()).convert("RGB")
        path = destination / f"{name}.png"
        image.save(path, optimize=True)
        print(f"PHOTO_REFERENCE {name}: {image.size} -> {path}")


if __name__ == "__main__":
    main()
