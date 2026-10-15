"""Research-only FiveK Expert-C RAW/TIFF paired-data pilot.

Downloads independently selected genuine expert-retouched pairs from the
MIT-Adobe FiveK research dataset. Dataset license permits research only.
Does NOT redistribute images, retouches or trained weights.
"""
from __future__ import annotations
import argparse
import csv
import hashlib
import io
import json
from pathlib import Path
from urllib.parse import urlparse
import numpy as np
import requests
from PIL import Image, ImageCms
from requests.adapters import HTTPAdapter
from urllib3.util.retry import Retry

SPLIT_URLS = {
 "train": "https://huggingface.co/datasets/yuukicammy/MIT-Adobe-FiveK/raw/main/training.json",
 "val": "https://huggingface.co/datasets/yuukicammy/MIT-Adobe-FiveK/raw/main/validation.json",
 "test": "https://huggingface.co/datasets/yuukicammy/MIT-Adobe-FiveK/raw/main/testing.json"
}
MAX_BYTES = 100 * 1024 * 1024
class OversizedPairAsset(ValueError):
    """A genuine dataset asset is too large for the bounded research pilot."""
def session():
    s=requests.Session()
    adapter=HTTPAdapter(max_retries=Retry(total=3,backoff_factor=2,
        status_forcelist=[429,500,502,503,504],allowed_methods=["GET"]))
    s.mount("https://",adapter)
    return s

def safe_url(value:str,extension:str):
    url=urlparse(value.replace("http://data.csail.mit.edu/","https://data.csail.mit.edu/"))
    if url.scheme!="https" or url.hostname!="data.csail.mit.edu" or not url.path.startswith("/graphics/fivek/img/") or not url.path.endswith(extension):
        raise ValueError("Untrusted or unsupported dataset URL")
    return url.geturl()

def download(s,url: str, out:Path):
    if out.exists() and 512<out.stat().st_size<MAX_BYTES:
        return out
    out.parent.mkdir(parents=True,exist_ok=True)
    tmp=out.with_suffix(".download")
    size=0
    with s.get(url,timeout=(20,120),stream=True) as response:
        response.raise_for_status()
        if int(response.headers.get("Content-Length","0"))>MAX_BYTES:
            raise OversizedPairAsset("Dataset asset exceeds size limit")
        with tmp.open("wb") as f:
            for chunk in response.iter_content(1024*256):
                if not chunk:continue
                size+=len(chunk)
                if size>MAX_BYTES:raise OversizedPairAsset("Dataset file too large")
                f.write(chunk)
    if size<512:raise ValueError("Truncated download")
    tmp.replace(out)
    return out

def source_srgb(raw_path:Path):
    import rawpy
    with rawpy.imread(str(raw_path)) as raw:
        array=raw.postprocess(output_bps=8,output_color=rawpy.ColorSpace.sRGB,
            use_camera_wb=True,no_auto_bright=True,gamma=(2.222,4.5))
    return Image.fromarray(array,"RGB")

def expert_srgb(expert_path:Path):
    with Image.open(expert_path) as file:
        icc=file.info.get("icc_profile")
        if not icc:raise ValueError("Expert TIFF missing ICC, cannot safely interpret ProPhoto")
        src=ImageCms.ImageCmsProfile(io.BytesIO(icc))
        rgb=ImageCms.profileToProfile(file.convert("RGB"),src,
            ImageCms.createProfile("sRGB"),outputMode="RGB")
        return rgb.copy()

def prepare(root:Path, train=12, val=4, test=4, seed=27,
            max_edge=384,test_offset=0):
    if min(train,val,test)<1 or max_edge<128 or test_offset<0:
        raise ValueError("Invalid research pilot size")
    root.mkdir(parents=True,exist_ok=True)
    s=session()
    counts={"train":train,"val":val,"test":test}
    manifest=[]
    diagnostics=[]
    for split,count in counts.items():
        response=s.get(SPLIT_URLS[split],timeout=(20,120))
        response.raise_for_status()
        records=response.json()
        # A fixed disjoint subset of the dataset's official split. This is
        # never selected using image content, validation scores or labels.
        items=sorted(records.items(),key=lambda x:hashlib.sha256(
            (str(seed)+":"+x[0]).encode()).hexdigest())
        # Independent, predeclared test offset after the earlier research
        # experiment: never select a prior exposed photo based on outcomes.
        if split=="test":items=items[test_offset:]
        if len(items)<count:raise ValueError("Insufficient official split records")
        admitted=0
        for name,item in items:
            if admitted>=count:break
            urls=item["urls"]
            dng_url=safe_url(urls["dng"],".dng")
            tif_url=safe_url(urls["tiff16"]["c"],".tif")
            raw_path=root/"raw"/(name+".dng")
            tif_path=root/"expert"/(name+".tif")
            try:
                raw=download(s,dng_url,raw_path)
                tiff=download(s,tif_url,tif_path)
            except OversizedPairAsset:
                print(json.dumps({"excluded_large_capture":item["id"]}),flush=True)
                for leftover in (raw_path,tif_path,
                     raw_path.with_suffix(".download"),tif_path.with_suffix(".download")):
                    leftover.unlink(missing_ok=True)
                continue
            source=source_srgb(raw)
            target=expert_srgb(tiff)
            sr=source.width/source.height; tr=target.width/target.height
            if abs(sr/tr-1)>.025:
                print(json.dumps({"excluded_unaligned_capture":item["id"],
                    "source_geometry":source.size,
                    "expert_geometry":target.size}),flush=True)
                raw.unlink(missing_ok=True)
                tiff.unlink(missing_ok=True)
                continue
            # Preserve ratio and reject crop mismatches; paired files must
            # depict the same composition even when RAW/TIFF dimensions vary.
            def shrink(im):
                x=im.copy();x.thumbnail((max_edge,max_edge),Image.Resampling.LANCZOS)
                return x
            source=shrink(source);target=shrink(target)
            if source.size!=target.size:
                target=target.resize(source.size,Image.Resampling.LANCZOS)
            file_id=str(item["id"])
            a=root/"pairs"/split/(file_id+"_source.png")
            b=root/"pairs"/split/(file_id+"_expert.png")
            a.parent.mkdir(parents=True,exist_ok=True)
            source.save(a,optimize=True);target.save(b,optimize=True)
            category="/".join(str(item.get("categories",{}).get(k,"unknown")) for k in ("location","light","subject"))
            rights="MIT-Adobe FiveK research only "+str(item.get("license","unknown"))
            manifest.append(dict(id="fivek_"+file_id,source=str(a.relative_to(root)),
                target=str(b.relative_to(root)),rights=rights,split=split,
                group="fivek_capture_"+file_id,category=category,preserve="no"))
            admitted+=1
            diagnostics.append(dict(id=file_id,split=split,raw_size=str(raw.stat().st_size),
               expert_size=str(tiff.stat().st_size),converted_geometry=str(source.size),
               license=item.get("license"),source_sha256=hashlib.sha256(a.read_bytes()).hexdigest(),
               expert_sha256=hashlib.sha256(b.read_bytes()).hexdigest()))
            print(json.dumps({"prepared":file_id,"split":split,
                "photo_category":category,"geometry":source.size}),flush=True)
            # The private paired sRGB training inputs are sufficient after
            # conversion. Do not retain large source archives on the runner.
            raw.unlink(missing_ok=True)
            tiff.unlink(missing_ok=True)
        if admitted!=count:
            raise ValueError(f"Could not find {count} valid aligned pairs for {split}")
    fieldnames=list(manifest[0])
    with (root/"manifest.csv").open("w",newline="") as f:
        writer=csv.DictWriter(f,fieldnames=fieldnames)
        writer.writeheader();writer.writerows(manifest)
    (root/"provenance-private.json").write_text(json.dumps(
       {"source":"MIT-Adobe FiveK official via research-only mirror metadata",
        "source_urls":SPLIT_URLS,"seed":seed,"test_offset":test_offset,
        "warning":"Research only. No trained model distribution or Android integration.",
        "records":diagnostics},indent=2))
    print(json.dumps({"complete":True,"counts":counts,"research_only":True}),flush=True)

if __name__=="__main__":
    p=argparse.ArgumentParser()
    p.add_argument("--root",type=Path,required=True)
    p.add_argument("--train",type=int,default=12)
    p.add_argument("--val",type=int,default=4)
    p.add_argument("--test",type=int,default=4)
    p.add_argument("--max-edge",type=int,default=384)
    p.add_argument("--test-offset",type=int,default=0)
    args=p.parse_args()
    prepare(args.root,args.train,args.val,args.test,
        max_edge=args.max_edge,test_offset=args.test_offset)
