"""Acquire a predeclared, disjoint test cohort. No training or target selection.

Keep all photos private. Full-resolution images are optional local research
inputs, never public artifacts. Reuse only training/validation pairs verbatim.
"""
import argparse
import csv
import hashlib
import json
import shutil
from pathlib import Path

try:
    from .prepare_fivek_pilot import (SPLIT_URLS, session, download, safe_url,
                                    source_srgb, expert_srgb, OversizedPairAsset)
except ImportError:
    from prepare_fivek_pilot import (SPLIT_URLS, session, download, safe_url,
                                   source_srgb, expert_srgb, OversizedPairAsset)
from PIL import Image


def assert_disjoint(ids, excluded):
    if len(ids) != len(set(ids)) or set(ids) & set(excluded):
        raise ValueError("Duplicate or previously exposed capture in new test cohort")


def prepare(previous, root, offset=128, count=16, full_count=4):
    if offset < 0 or count < 1 or not 0 <= full_count <= count:
        raise ValueError("Invalid cohort specification")
    if root.exists():
        raise FileExistsError("Use a new output directory, not a mixed old cohort")
    old = list(csv.DictReader((previous / "manifest.csv").open()))
    excluded = {r["id"] for r in old}
    root.mkdir(parents=True)
    rows = []
    for row in old:
        if row["split"] == "test":
            continue
        for k in ("source", "target"):
            dst = root / row[k]
            dst.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(previous / row[k], dst)
        rows.append(row)
    s = session()
    response = s.get(SPLIT_URLS["test"], timeout=(20, 120))
    response.raise_for_status()
    records = response.json()
    items = sorted(records.items(), key=lambda x: hashlib.sha256(
        ("27:" + x[0]).encode()).hexdigest())
    admitted, provenance = [], []
    for rank, (name, item) in enumerate(items[offset:], offset):
        if len(admitted) == count:
            break
        ident = "fivek_" + str(item["id"])
        # A rank offset is not itself proof of non-overlap after exclusions.
        assert_disjoint(admitted + [ident], excluded)
        raw = root / "raw" / (name + ".dng")
        tif = root / "expert" / (name + ".tif")
        try:
            download(s, safe_url(item["urls"]["dng"], ".dng"), raw)
            download(s, safe_url(item["urls"]["tiff16"]["c"], ".tif"), tif)
            source, target = source_srgb(raw), expert_srgb(tif)
            if abs((source.width / source.height) / (target.width / target.height) - 1) > .025:
                provenance.append({"id": ident, "rank": rank, "excluded": "aspect mismatch"})
                continue
            original_size = source.size
            if len(admitted) < full_count:
                folder = root / "full" / "test"
                folder.mkdir(parents=True, exist_ok=True)
                source.save(folder / (str(item["id"]) + "_source.png"))
                target.resize(source.size, Image.Resampling.LANCZOS).save(
                    folder / (str(item["id"]) + "_expert.png"))
            source.thumbnail((384, 384), Image.Resampling.LANCZOS)
            target.thumbnail((384, 384), Image.Resampling.LANCZOS)
            target = target.resize(source.size, Image.Resampling.LANCZOS)
            a = Path("pairs/test") / (str(item["id"]) + "_source.png")
            b = Path("pairs/test") / (str(item["id"]) + "_expert.png")
            (root / a).parent.mkdir(parents=True, exist_ok=True)
            source.save(root / a, optimize=True)
            target.save(root / b, optimize=True)
            rows.append(dict(id=ident, source=str(a), target=str(b),
                rights="MIT-Adobe FiveK research only " + str(item.get("license")),
                split="test", group="fivek_capture_" + str(item["id"]),
                category="/".join(str(item.get("categories", {}).get(k, "unknown"))
                    for k in ("location", "light", "subject")), preserve="no"))
            admitted.append(ident)
            provenance.append({"id": ident, "rank": rank, "full_geometry": original_size,
                "source_sha256": hashlib.sha256((root/a).read_bytes()).hexdigest(),
                "expert_sha256": hashlib.sha256((root/b).read_bytes()).hexdigest()})
            print(json.dumps({"prepared": ident, "rank": rank}), flush=True)
        except OversizedPairAsset:
            provenance.append({"id": ident, "rank": rank, "excluded": "size limit"})
        finally:
            for path in (raw, tif, raw.with_suffix(".download"), tif.with_suffix(".download")):
                path.unlink(missing_ok=True)
    if len(admitted) != count:
        raise ValueError("Insufficient new captures")
    assert_disjoint(admitted, excluded)
    with (root / "manifest.csv").open("w", newline="") as f:
        w = csv.DictWriter(f, fieldnames=list(rows[0])); w.writeheader(); w.writerows(rows)
    (root / "provenance-private.json").write_text(json.dumps({
        "test_offset": offset, "seed": 27, "test_count": count,
        "full_resolution_count": full_count,
        "metadata_sha256": hashlib.sha256(response.content).hexdigest(),
        "excluded_previously_exposed_ids": sorted(excluded), "records": provenance,
        "research_only": True}, indent=2) + "\n")


if __name__ == "__main__":
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--previous", type=Path, required=True)
    p.add_argument("--root", type=Path, required=True)
    p.add_argument("--offset", type=int, default=128)
    p.add_argument("--count", type=int, default=16)
    p.add_argument("--full-count", type=int, default=4)
    a = p.parse_args(); prepare(a.previous, a.root, a.offset, a.count, a.full_count)
