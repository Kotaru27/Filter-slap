"""Expert-paired licensed sRGB dataset with capture-group holdouts."""
from __future__ import annotations
from dataclasses import dataclass
from pathlib import Path
import csv
import hashlib
import io
import random
import numpy as np
from PIL import Image, ImageOps, ImageCms
import torch
from torch.utils.data import Dataset

COLUMNS = {"id","source","target","rights","split","group","category","preserve"}
SPLITS = {"train","val","test"}

@dataclass(frozen=True)
class Pair:
    ident: str
    source: Path
    target: Path
    rights: str
    split: str
    group: str
    category: str
    preserve: bool

def _inside(root: Path, value: str) -> Path:
    path = (root/value).resolve()
    if not path.is_relative_to(root.resolve()):
        raise ValueError("dataset paths cannot escape manifest directory")
    if not path.is_file():
        raise FileNotFoundError(path)
    return path

def read_manifest(path: Path, min_train: int=1, min_val: int=1,
                  min_test: int=1) -> list[Pair]:
    path=Path(path).resolve()
    with path.open(newline="",encoding="utf-8-sig") as file:
        reader=csv.DictReader(file)
        if not COLUMNS.issubset(reader.fieldnames or []):
            raise ValueError("Manifest missing "+str(COLUMNS-set(reader.fieldnames or [])))
        rows=[]
        for line,row in enumerate(reader,2):
            ident=(row["id"] or "").strip()
            split=(row["split"] or "").strip().lower()
            rights=(row["rights"] or "").strip()
            preserve=(row["preserve"] or "").strip().lower()
            if not ident or not all(c.isalnum() or c in "_-" for c in ident):
                raise ValueError(f"Line {line}: invalid id")
            if split not in SPLITS:
                raise ValueError(f"Line {line}: invalid split")
            if len(rights)<8 or rights.lower() in {"unknown","none","unverified","personal"}:
                raise ValueError(f"Line {line}: documented rights required")
            if preserve not in {"yes","no"}:
                raise ValueError(f"Line {line}: preserve must be yes/no")
            group=(row["group"] or "").strip()
            category=(row["category"] or "").strip()
            if not group or not category:
                raise ValueError(f"Line {line}: group and category required")
            rows.append(Pair(ident,_inside(path.parent,row["source"]),
                             _inside(path.parent,row["target"]),rights,split,
                             group,category,preserve=="yes"))
    if len({x.ident for x in rows})!=len(rows):
        raise ValueError("duplicate IDs")
    group_split={}
    content_split={}
    for item in rows:
        if group_split.setdefault(item.group,item.split)!=item.split:
            raise ValueError("capture-group leakage across splits")
        digest=hashlib.sha256(item.source.read_bytes()).hexdigest()
        if content_split.setdefault(digest,item.split)!=item.split:
            raise ValueError("identical source files leak across splits")
    counts={split:sum(x.split==split for x in rows) for split in SPLITS}
    if counts["train"]<min_train or counts["val"]<min_val or counts["test"]<min_test:
        raise ValueError("insufficient declared splits: "+str(counts))
    return rows

def srgb(path: Path) -> Image.Image:
    with Image.open(path) as file:
        oriented=ImageOps.exif_transpose(file)
        icc=oriented.info.get("icc_profile")
        if icc:
            try:
                src=ImageCms.ImageCmsProfile(io.BytesIO(icc))
                return ImageCms.profileToProfile(oriented,src,
                                                 ImageCms.createProfile("sRGB"),
                                                 outputMode="RGB")
            except (OSError,ValueError,TypeError) as exc:
                raise ValueError(f"Invalid ICC in {path}") from exc
        # Dataset curator must confirm untagged input/output really are sRGB.
        return oriented.convert("RGB")

class ExpertPairs(Dataset):
    def __init__(self, rows: list[Pair], split: str, size: int=256, seed: int=0):
        self.items=[x for x in rows if x.split==split]
        if not self.items or size<64:
            raise ValueError("missing pairs or size too small")
        self.size,self.split,self.seed=size,split,seed

    def __len__(self):
        return len(self.items)

    def __getitem__(self,index):
        item=self.items[index]
        source,target=srgb(item.source),srgb(item.target)
        if source.size!=target.size:
            raise ValueError(f"Unaligned expert pair {item.ident}")
        a=np.asarray(source.resize((self.size,self.size),Image.Resampling.LANCZOS),
                     np.float32).copy()/255.
        b=np.asarray(target.resize((self.size,self.size),Image.Resampling.LANCZOS),
                     np.float32).copy()/255.
        if self.split=="train" and random.Random(self.seed+index*982451653).randrange(2):
            a=np.flip(a,axis=1).copy()
            b=np.flip(b,axis=1).copy()
        return (torch.from_numpy(a.transpose(2,0,1).copy()),
                torch.from_numpy(b.transpose(2,0,1).copy()),
                torch.tensor(float(item.preserve),dtype=torch.float32))
