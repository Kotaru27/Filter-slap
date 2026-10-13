"""CPU-first, benchmark-only Image-Adaptive 3D LUT inference.

Faithfully mirrors the *paired sRGB* upstream Classifier topology and 3-basis
LUT weighted sum. No custom CUDA extension; trilinear interpolation is NumPy.
This module is NOT used by the Android application.

Upstream: https://github.com/HuiZeng/Image-Adaptive-3DLUT
License of upstream reference implementation: Apache-2.0. Validate weight
rights and the image dataset's rights separately before commercial use.
"""
from __future__ import annotations

from dataclasses import dataclass
import hashlib
import io
from pathlib import Path
import time
from typing import Any

import numpy as np
from PIL import Image, ImageOps, ImageCms


def _torch():
    try:
        import torch
        from torch import nn
    except ImportError as exc:
        raise RuntimeError("PyTorch required for pretrained inference. Install research/v6/requirements-model.txt") from exc
    return torch, nn


def build_classifier():
    """Exact paired Classifier layout from upstream models_x.py (not unpaired)."""
    torch, nn = _torch()
    def down(a: int, b: int, norm: bool) -> list:
        layers = [nn.Conv2d(a, b, 3, stride=2, padding=1), nn.LeakyReLU(0.2)]
        if norm:
            layers.append(nn.InstanceNorm2d(b, affine=True))
        return layers
    class Classifier(nn.Module):
        def __init__(self):
            super().__init__()
            self.model = nn.Sequential(
                nn.Upsample(size=(256, 256), mode="bilinear"),
                nn.Conv2d(3, 16, 3, stride=2, padding=1),
                nn.LeakyReLU(0.2),
                nn.InstanceNorm2d(16, affine=True),
                *down(16, 32, True),
                *down(32, 64, True),
                *down(64, 128, True),
                *down(128, 128, False),
                nn.Dropout(p=0.5),
                nn.Conv2d(128, 3, 8, padding=0),
            )
        def forward(self, x):
            return self.model(x)
    return Classifier()



def to_srgb(original: Image.Image) -> Image.Image:
    """Honor embedded ICC profiles before RGB LUT inference.

    Unprofiled inputs are explicitly *assumed* to be sRGB, never guessed
    to be Display-P3 or a working-space RAW image. Reject invalid ICC
    rather than silently use wrong RGB primaries.
    """
    oriented = ImageOps.exif_transpose(original)
    icc = oriented.info.get("icc_profile")
    if not icc:
        return oriented.convert("RGB")
    try:
        source_profile = ImageCms.ImageCmsProfile(io.BytesIO(icc))
        srgb_profile = ImageCms.createProfile("sRGB")
        return ImageCms.profileToProfile(oriented, source_profile, srgb_profile, outputMode="RGB")
    except (OSError, ValueError, TypeError) as exc:
        raise ValueError("Unreadable embedded ICC profile; cannot safely interpret colors") from exc


def sha256(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for part in iter(lambda: f.read(1024 * 1024), b""):
            h.update(part)
    return h.hexdigest()


def interpolate_lut(rgb: np.ndarray, lut: np.ndarray, chunk_pixels: int = 250_000) -> np.ndarray:
    """Trilinear lookup, basis layout [output RGB, BLUE, GREEN, RED].

    rgb: H,W,3 float in 0..1. lut: 3,D,D,D. Does not resample spatially.
    Preserves out-of-range LUT outputs until caller explicitly clips them.
    """
    a = np.asarray(rgb, dtype=np.float32)
    b = np.asarray(lut, dtype=np.float32)
    if a.ndim != 3 or a.shape[-1] != 3 or not np.isfinite(a).all():
        raise ValueError("Input must be finite HxWx3 RGB")
    if b.ndim != 4 or b.shape[0] != 3 or len(set(b.shape[1:])) != 1 or b.shape[1] < 2 or not np.isfinite(b).all():
        raise ValueError("LUT must be finite 3xDxDxD")
    if a.min() < 0.0 or a.max() > 1.0:
        raise ValueError("Input RGB must be in [0,1]")
    if chunk_pixels <= 0:
        raise ValueError("chunk_pixels must be positive")
    d = b.shape[1]
    # LUT coordinate order is B,G,R, whereas NumPy input pixels are R,G,B.
    flat = a.reshape(-1, 3)
    output = np.empty_like(flat)
    lut_flat = b.reshape(3, -1)
    for start in range(0, len(flat), chunk_pixels):
        end = min(start + chunk_pixels, len(flat))
        points = flat[start:end][:, ::-1] * (d - 1)
        lo = np.floor(points).astype(np.int32)
        hi = np.minimum(lo + 1, d - 1)
        frac = points - lo
        accum = np.zeros((end-start, 3), dtype=np.float32)
        for bz in (0, 1):
            bb = hi[:, 0] if bz else lo[:, 0]
            wb = frac[:, 0] if bz else 1-frac[:, 0]
            for gy in (0, 1):
                gg = hi[:, 1] if gy else lo[:, 1]
                wg = frac[:, 1] if gy else 1-frac[:, 1]
                for rx in (0, 1):
                    rr = hi[:, 2] if rx else lo[:, 2]
                    wr = frac[:, 2] if rx else 1-frac[:, 2]
                    idx = (bb*d + gg)*d + rr
                    accum += lut_flat[:, idx].T * (wb*wg*wr)[:, None]
        output[start:end] = accum
    return output.reshape(a.shape)


def identity_lut(dim: int = 33) -> np.ndarray:
    if dim < 2:
        raise ValueError("dim must be >=2")
    b, g, r = np.meshgrid(*([np.linspace(0, 1, dim, dtype=np.float32)]*3), indexing="ij")
    return np.stack([r, g, b], axis=0)


@dataclass(frozen=True)
class Inference:
    output: Image.Image
    coefficients: tuple[float, float, float]
    out_of_range_fraction: float
    elapsed_ms: float
    model_sha256: tuple[str, str]


class AdaptiveLUT:
    def __init__(self, model_dir: Path):
        torch, _ = _torch()
        root = Path(model_dir)
        lut_path, classifier_path = root / "LUTs.pth", root / "classifier.pth"
        if not lut_path.is_file() or not classifier_path.is_file():
            raise FileNotFoundError("Expected LUTs.pth and classifier.pth in --model-dir. Run fetch_weights.py first.")
        # Weight-only loading: no arbitrary Python object unpickling.
        weights = torch.load(lut_path, map_location="cpu", weights_only=True)
        basis = []
        for k in ("0", "1", "2"):
            layer = weights[k]["LUT"]
            arr = np.asarray(layer.detach().cpu(), dtype=np.float32)
            if arr.shape != (3, 33, 33, 33) or not np.isfinite(arr).all():
                raise ValueError(f"Invalid basis LUT {k}: {arr.shape}")
            basis.append(arr)
        self.bases = np.stack(basis, axis=0)
        self.classifier = build_classifier()
        raw = torch.load(classifier_path, map_location="cpu", weights_only=True)
        self.classifier.load_state_dict(raw, strict=True)
        self.classifier.eval()
        self.model_sha256 = (sha256(lut_path), sha256(classifier_path))

    def enhance(self, original: Image.Image) -> Inference:
        torch, _ = _torch()
        t0 = time.monotonic()
        source = to_srgb(original)
        # The published model resamples the source to 256x256 in its first layer.
        # Feed original dimensions to F.interpolate whenever practical for parity;
        # avoid huge input tensors by downsampling only images > 5 MP first.
        if source.width * source.height > 5_000_000:
            inference_img = source.copy()
            inference_img.thumbnail((2048, 2048), Image.Resampling.BILINEAR)
        else:
            inference_img = source
        classifier_rgb = np.asarray(inference_img, dtype=np.float32)/255.0
        input_tensor = torch.from_numpy(classifier_rgb).permute(2, 0, 1).unsqueeze(0)
        with torch.inference_mode():
            coefficients = self.classifier(input_tensor).reshape(-1).numpy()
        if coefficients.shape != (3,) or not np.isfinite(coefficients).all():
            raise ValueError("Classifier coefficients are not finite 3-vector")
        combined = np.einsum("i,icbgr->cbgr", coefficients, self.bases, optimize=True)
        output = np.empty((source.height, source.width, 3), dtype=np.uint8)
        over = 0
        # Tiled rendering for bounded memory, full spatial resolution preserved.
        for y0 in range(0, source.height, 256):
            y1 = min(source.height, y0+256)
            arr = np.asarray(source.crop((0,y0,source.width,y1)), dtype=np.float32)/255.0
            raw = interpolate_lut(arr, combined)
            over += np.count_nonzero((raw < 0) | (raw > 1))
            output[y0:y1] = np.rint(np.clip(raw, 0, 1)*255).astype(np.uint8)
        return Inference(Image.fromarray(output,"RGB"), tuple(float(x) for x in coefficients),
                         over/(source.height*source.width*3), (time.monotonic()-t0)*1000,
                         self.model_sha256)
