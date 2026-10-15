"""Torch research prototype for learned, guided photo retouching.

Predict global or spatial RGB affine grids from whole-frame visual features.
Both modes begin at a mathematically exact identity edit. This is not an
Android model or photographic-quality claim.
"""
from __future__ import annotations
import torch
from torch import nn
from torch.nn import functional as F


class LearnedPhotoEditor(nn.Module):
    def __init__(self, mode: str = "spatial", bins: int = 8, grid_size: int = 16):
        super().__init__()
        if mode not in ("global", "spatial"):
            raise ValueError("mode must be global or spatial")
        if bins < 2 or grid_size < 2:
            raise ValueError("bins and grid_size must be >= 2")
        self.mode, self.bins, self.grid_size = mode, bins, grid_size
        self.encoder = nn.Sequential(
            nn.Conv2d(3, 24, 3, stride=2, padding=1), nn.GELU(),
            nn.Conv2d(24, 32, 3, stride=2, padding=1), nn.GELU(),
            nn.Conv2d(32, 48, 3, stride=2, padding=1), nn.GELU(),
            nn.Conv2d(48, 48, 3, padding=1), nn.GELU(),
        )
        self.head = nn.Conv2d(48, 12*bins, 1)
        nn.init.zeros_(self.head.weight)
        nn.init.zeros_(self.head.bias)

    def coefficients(self, source: torch.Tensor) -> torch.Tensor:
        if source.ndim != 4 or source.shape[1] != 3:
            raise ValueError("input must be BCHW RGB")
        if not torch.isfinite(source).all():
            raise ValueError("nonfinite input")
        context = F.interpolate(source, (256, 256), mode="bilinear", align_corners=False)
        features = self.encoder(context)
        size = 1 if self.mode == "global" else self.grid_size
        features = F.adaptive_avg_pool2d(features, (size, size))
        return self.head(features).reshape(source.shape[0], 12, self.bins, size, size)

    @staticmethod
    def _render_strip(source: torch.Tensor, grid: torch.Tensor,
                      y_begin: int, total_height: int) -> torch.Tensor:
        b, c, h, w = source.shape
        if c != 3 or y_begin < 0 or y_begin+h > total_height:
            raise ValueError("invalid image tile")
        x = torch.linspace(-1, 1, w, device=source.device, dtype=source.dtype)
        full_y = torch.linspace(-1, 1, total_height, device=source.device,
                                dtype=source.dtype)
        yy, xx = torch.meshgrid(full_y[y_begin:y_begin+h], x, indexing="ij")
        lum = .2126*source[:, 0]+.7152*source[:, 1]+.0722*source[:, 2]
        coords = torch.stack((xx.expand(b,-1,-1), yy.expand(b,-1,-1),
                              lum*2-1), dim=-1).unsqueeze(1)
        # Trilinear sample of learned RGB affine coefficients in (x,y,luminance).
        coeff = F.grid_sample(grid, coords, mode="bilinear", padding_mode="border",
                              align_corners=True).squeeze(2).reshape(b,3,4,h,w)
        delta = (coeff[:, :, :3]*source.unsqueeze(1)).sum(2) + coeff[:, :, 3]
        return (source + .35*torch.tanh(delta)).clamp(0,1)

    def forward(self, source: torch.Tensor, tile_rows: int = 0) -> torch.Tensor:
        if source.min().item() < 0 or source.max().item() > 1:
            raise ValueError("source RGB must be in [0,1]")
        grid = self.coefficients(source)
        h = source.shape[2]
        if tile_rows <= 0 or tile_rows >= h:
            return self._render_strip(source,grid,0,h)
        return torch.cat(
            [self._render_strip(source[:,:,top:top+tile_rows],grid,top,h)
             for top in range(0,h,tile_rows)], dim=2)


def objective(source: torch.Tensor, output: torch.Tensor, target: torch.Tensor,
              preserve: torch.Tensor) -> tuple[torch.Tensor, dict[str,float]]:
    """Expert-target pixel objective with edge and already-good safeguards.

    Not a metric of human photographic preference.
    """
    if not (source.shape == output.shape == target.shape):
        raise ValueError("aligned source/target/output required")
    b = source.shape[0]
    l1 = (output-target).abs().flatten(1).mean(1).mean()
    structural = (
        (output[:,:,1:]-output[:,:,:-1]-target[:,:,1:]+target[:,:,:-1]).abs().mean()
        + (output[:,:,:,1:]-output[:,:,:,:-1]-target[:,:,:,1:]+target[:,:,:,:-1]).abs().mean()
    )
    preservation = ((output-source).abs().flatten(1).mean(1)*preserve.reshape(b)).mean()
    loss = l1+.14*structural+.60*preservation
    return loss, {"expert_l1":float(l1.detach()),
                  "edge_l1":float(structural.detach()),
                  "preserve_l1":float(preservation.detach())}
