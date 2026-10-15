"""Per-capture, multi-metric audit; never an automatic aesthetic release gate.

Numerical reports may be published. Images and blind answer keys stay local.
No oracle measurement in this module is used by the editor or strength search.
"""
import argparse
import csv
import hashlib
import json
from pathlib import Path
import numpy as np
from PIL import Image, ImageDraw

STRENGTHS = (1., .75, .5, .25, .125)
LIMITS = dict(mean_change=35., new_clipping=.003, new_crushing=.003,
              hue_shift=.04, vivid_boost=.04)


def pixels(path):
    with Image.open(path) as im:
        return np.asarray(im.convert("RGB"), dtype=np.float32)


def luma(x):
    return x @ np.array([.2126, .7152, .0722], dtype=np.float32)


def damage(source, output):
    """Independent implementation of the ORIGINAL V9 byte-domain guard."""
    a, b = source.astype(np.float32), output.astype(np.float32)
    ar, br = np.ptp(a, axis=-1), np.ptp(b, axis=-1)
    u, v = a[..., 0]-a[..., 1], (a[..., 0]+a[..., 1]-2*a[..., 2])/1.7320508
    uu, vv = b[..., 0]-b[..., 1], (b[..., 0]+b[..., 1]-2*b[..., 2])/1.7320508
    return dict(mean_change=float(np.abs(a-b).mean()),
        new_clipping=float(((a.max(-1)<252)&(b.max(-1)>=252)).mean()),
        new_crushing=float(((luma(a)>=5)&(luma(b)<5)).mean()),
        hue_shift=float(((ar>40)&(br>40)&((u*uu+v*vv)<.94*np.sqrt((u*u+v*v)*(uu*uu+vv*vv)))).mean()),
        vivid_boost=float(((ar>114)&(br>ar+26)).mean()))


def safe(d):
    return all(np.isfinite(d[k]) and d[k] <= v for k, v in LIMITS.items())


def attenuate(source, output, strength):
    # Android rounds the full model result to bytes BEFORE attenuation.
    return np.floor(source + strength*(output-source)+.5).clip(0, 255)


def lab(rgb):
    x = rgb/255.
    x = np.where(x <= .04045, x/12.92, ((x+.055)/1.055)**2.4)
    xyz = x @ np.array([[.4124564,.3575761,.1804375],
                       [.2126729,.7151522,.0721750],
                       [.0193339,.1191920,.9503041]]).T
    t = xyz/np.array([.95047,1.,1.08883])
    f = np.where(t>(6/29)**3, np.cbrt(t), t/(3*(6/29)**2)+4/29)
    return np.stack([116*f[...,1]-16,500*(f[...,0]-f[...,1]),
                     200*(f[...,1]-f[...,2])],axis=-1)


def measures(source, output, expert):
    y, yo, ye = luma(source), luma(output), luma(expert)
    lo, le = lab(output), lab(expert)
    def region(mask):
        return float(np.abs(output-expert)[mask].mean()) if mask.any() else None
    def grad(x):
        return np.concatenate((np.diff(x,axis=0).ravel(),np.diff(x,axis=1).ravel()))
    return dict(rgb_l1=float(np.abs(output-expert).mean()),
        luma_l1=float(np.abs(yo-ye).mean()), luma_bias=float((yo-ye).mean()),
        lab_delta_e76=float(np.linalg.norm(lo-le,axis=-1).mean()),
        lab_chroma_error=float(np.linalg.norm(lo[...,1:]-le[...,1:],axis=-1).mean()),
        shadow_rgb_l1=region(y<64), midtone_rgb_l1=region((y>=64)&(y<192)),
        highlight_rgb_l1=region(y>=192),
        dark_lift=float((yo-y)[y<64].mean()) if (y<64).any() else None,
        dark_target_lift=float((ye-y)[y<64].mean()) if (y<64).any() else None,
        mean_luma=float(yo.mean()), p01_luma=float(np.quantile(yo,.01)),
        p99_luma=float(np.quantile(yo,.99)),
        edge_l1_to_expert=float(np.abs(grad(yo)-grad(ye)).mean()),
        edge_l1_to_source=float(np.abs(grad(yo)-grad(y)).mean()),
        edge_energy=float(np.abs(grad(yo)).mean()), **damage(source,output))


def decision_map(path, ids):
    with path.open(newline="") as f:
        rows = list(csv.DictReader(f))
    mapped = {r["id"]: r for r in rows}
    if len(rows)!=len(mapped) or set(mapped)!=set(ids):
        raise ValueError("Native decision IDs must match every capture exactly")
    return mapped


def audit(data, models, native, baselines, out, visuals=None):
    summary = json.loads((models/"public_aggregate_metrics.json").read_text())
    mode = summary["winning_mode"]
    sources = sorted((data/"pairs/test").glob("*_source.png"))
    ids = ["fivek_"+p.name.removesuffix("_source.png") for p in sources]
    if not ids or len(ids)!=summary["n_heldout_test"]:
        raise ValueError("Test count mismatch")
    decisions = decision_map(native/"decisions.csv", ids)
    records = []
    for ident, path in zip(ids, sources):
        target_path = path.with_name(path.name.replace("_source", "_expert"))
        source, expert = pixels(path), pixels(target_path)
        learned = pixels(models/mode/"test"/(ident+".png"))
        images = {"original": source, **{name:pixels(folder/(ident+".png"))
                   for name,folder in baselines.items()}, "learned_full": learned}
        d = decisions[ident]
        alpha = float(d["strength"])
        if d["decision"]=="accepted":
            if alpha not in STRENGTHS or not safe({k:float(d[k]) for k in LIMITS}):
                raise ValueError("Invalid native accepted decision")
            selected = pixels(native/(ident+".png"))
        elif d["decision"]=="rejected" and alpha==0:
            if (native/(ident+".png")).exists():
                raise ValueError("Stale rejected image")
            selected = images["v9"]
        else:
            raise ValueError("Unknown native decision")
        images["guarded"] = selected
        if any(a.shape!=expert.shape for a in images.values()):
            raise ValueError("Paired geometry mismatch")
        measured = {name:measures(source,a,expert) for name,a in images.items()}
        # This is diagnostic hindsight ONLY. Never feed target errors to editor.
        curve = []
        for strength in (0., *STRENGTHS):
            a = attenuate(source, learned, strength)
            dd = damage(source,a)
            curve.append(dict(strength=strength, python_guard_safe=safe(dd),
                rgb_l1=float(np.abs(a-expert).mean()), **dd))
        records.append(dict(id=ident, width=source.shape[1], height=source.shape[0],
            source_sha256=hashlib.sha256(path.read_bytes()).hexdigest(),
            expert_sha256=hashlib.sha256(target_path.read_bytes()).hexdigest(),
            decision=d["decision"], strength=alpha, metrics=measured,
            diagnostic_strength_curve_not_selection=curve,
            android_vs_torch_at_selected_strength_l1=float(np.abs(selected-attenuate(source,learned,alpha)).mean())
                if alpha else None))
        if visuals:
            visuals.mkdir(parents=True,exist_ok=True)
            ordered = {"original":source, **{k:images[k] for k in baselines},
                       "guarded":selected, "expert":expert}
            card = Image.new("RGB",(400*len(ordered),340),(25,25,25))
            draw=ImageDraw.Draw(card)
            for i,(name,a) in enumerate(ordered.items()):
                im=Image.fromarray(a.astype(np.uint8)); im.thumbnail((390,300),Image.Resampling.LANCZOS)
                card.paste(im,(i*400+(400-im.width)//2,35+(300-im.height)//2))
                draw.text((i*400+8,8),f"{ident} | {name}",fill="white")
            card.save(visuals/(ident+".png"))
    comparisons = {}
    for baseline in baselines:
        b=np.array([r["metrics"][baseline]["rgb_l1"] for r in records])
        a=np.array([r["metrics"]["guarded"]["rgb_l1"] for r in records])
        margin=b-a
        draws=np.random.default_rng(101).integers(0,len(a),(4000,len(a)))
        comparisons[baseline]=dict(mean_baseline_rgb_l1=float(b.mean()),
            mean_guarded_rgb_l1=float(a.mean()), relative_rgb_l1_reduction=float(margin.mean()/b.mean()),
            improved_ids=[r["id"] for r,d in zip(records,margin) if d>.00255],
            degraded_ids=[r["id"] for r,d in zip(records,margin) if d<-.00255],
            mean_margin_bootstrap95=np.quantile(margin[draws].mean(1),[.025,.975]).tolist())
    result=dict(metric_units="8-bit levels, except Lab and fractions", count=len(records),
        comparisons=comparisons, cases=records, human_votes=0, release_approved=False,
        dark_regions_are_luminance_masks_not_semantic_material_or_skin_labels=True,
        rgb_lab_and_edges_do_not_measure_aesthetic_preference=True)
    out.parent.mkdir(parents=True,exist_ok=True)
    out.write_text(json.dumps(result,indent=2,allow_nan=False)+"\n")
    print(json.dumps(comparisons,indent=2),flush=True)
    return result


if __name__=="__main__":
    p=argparse.ArgumentParser(description=__doc__)
    for name in ("data","models","native","v9","out"):
        p.add_argument("--"+name,type=Path,required=True)
    p.add_argument("--v93",type=Path)
    p.add_argument("--visuals",type=Path)
    a=p.parse_args(); b={"v9":a.v9}
    if a.v93: b["v93"]=a.v93
    audit(a.data,a.models,a.native,b,a.out,a.visuals)
