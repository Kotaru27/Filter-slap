"""Download only the pinned upstream *paired sRGB* model weights.

Pin both repository commit and Git blob object SHA-1, not a moving branch.
The bytes are not part of the Filter Slap source and are never committed.
"""
from __future__ import annotations
import argparse
import hashlib
from pathlib import Path
import urllib.request

UPSTREAM_COMMIT = "b491f6df64a588864739a157db271e5c848e1805"
# SHA-1 of complete git blob (header + file bytes) from upstream GitHub API.
EXPECTED_BLOBS = {
    "LUTs.pth": "0830dceee2256ea5d0965c029945c329fac82a26",
    "classifier.pth": "439f2ada316f45d1d28dd86a161790aa3b4e334b",
}
EXPECTED_SHA256 = {
    "LUTs.pth": "c1bb2bc4b7239c1a7e96159f5923123ba796b1fceb0b8c3132b423ea825b821a",
    "classifier.pth": "bae9865395625ecae58cfe86147e521093bb1e29e7b2544e02adb238b8035021",
}


def sha256_matches(data: bytes, name: str) -> bool:
    return hashlib.sha256(data).hexdigest() == EXPECTED_SHA256[name]


def git_blob_sha1(data: bytes) -> str:
    return hashlib.sha1(f"blob {len(data)}\0".encode("ascii")+data).hexdigest()


def fetch_weights(directory: Path) -> None:
    directory.mkdir(parents=True, exist_ok=True)
    for name, expected in EXPECTED_BLOBS.items():
        if len(expected)!=40 or not all(x in '0123456789abcdef' for x in expected):
            raise RuntimeError("Upstream blob pin must be supplied before download")
        path=directory/name
        if path.exists() and git_blob_sha1(path.read_bytes())==expected and sha256_matches(path.read_bytes(),name):
            print(f"already verified: {name}")
            continue
        url=f"https://raw.githubusercontent.com/HuiZeng/Image-Adaptive-3DLUT/{UPSTREAM_COMMIT}/pretrained_models/sRGB/{name}"
        req=urllib.request.Request(url, headers={"User-Agent":"FilterSlap-V6-benchmark/1.0"})
        with urllib.request.urlopen(req, timeout=45) as response:
            data=response.read(4_000_000)
        actual=git_blob_sha1(data)
        if actual!=expected or not sha256_matches(data,name):
            raise ValueError(f"Checksum mismatch for {name}: git blob {actual}, sha256 {hashlib.sha256(data).hexdigest()}")
        temp=path.with_suffix(".tmp")
        temp.write_bytes(data)
        temp.replace(path)
        print(f"verified {name}: git blob {actual}; sha256 {hashlib.sha256(data).hexdigest()}")

if __name__ == "__main__":
    parser=argparse.ArgumentParser()
    parser.add_argument("--output",type=Path,default=Path("research/v6/model_weights"))
    fetch_weights(parser.parse_args().output)
