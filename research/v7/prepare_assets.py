"""Verify and bundle the actual pretrained foreground model; build-time download only."""
from pathlib import Path
import argparse
import hashlib
import urllib.request

URL = "https://github.com/danielgatis/rembg/releases/download/v0.0.0/u2netp.onnx"
SHA256 = "309c8469258dda742793dce0ebea8e6dd393174f89934733ecc8b14c76f4ddd8"
MD5 = "8e83ca70e441ab06c318d82300c84806"

def prepare(output: Path) -> None:
    output.mkdir(parents=True, exist_ok=True)
    target = output / "v7_foreground.onnx"
    if target.exists() and hashlib.sha256(target.read_bytes()).hexdigest() == SHA256:
        data = target.read_bytes()
    else:
        request = urllib.request.Request(URL, headers={"User-Agent": "FilterSlap-assets/7"})
        with urllib.request.urlopen(request, timeout=60) as response:
            data = response.read(5_000_001)
    if len(data) != 4_574_861 or hashlib.sha256(data).hexdigest() != SHA256 or hashlib.md5(data).hexdigest() != MD5:
        raise ValueError("Foreground model checksum/size mismatch")
    import onnxruntime as ort
    import numpy as np
    session = ort.InferenceSession(data, providers=["CPUExecutionProvider"])
    for seed in (11, 73):
        probe = np.random.default_rng(seed).random((1, 3, 320, 320), dtype=np.float32)
        output_tensor = session.run(None, {session.get_inputs()[0].name: probe})[0]
        if output_tensor.shape != (1, 1, 320, 320) or not np.isfinite(output_tensor).all():
            raise ValueError("Invalid foreground model output")
    temporary = target.with_suffix(".tmp")
    temporary.write_bytes(data)
    temporary.replace(target)
    (output / "v7_foreground_sha256.txt").write_text(SHA256 + "\n")
    print(f"U2NETP foreground model verified: {len(data)} bytes; SHA-256 {SHA256}")

if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--output-dir", type=Path, default=Path("app/src/main/assets"))
    prepare(parser.parse_args().output_dir)
