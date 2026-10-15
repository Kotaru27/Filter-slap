"""Encrypt research comparison images for private investigator review.

The public workflow never uploads readable photographs or trained weights.
The private key is held outside GitHub. Only allowlisted comparison PNG/JPGs
are packed; checkpoints, ONNX files, source archives and answer keys excluded.
"""
import argparse
import io
import json
import os
import tarfile
from pathlib import Path
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import padding
from cryptography.hazmat.primitives.ciphers.aead import AESGCM


def seal(folder, public_key, output):
    files=sorted(p for p in folder.rglob("*") if p.is_file())
    if not files or any(p.suffix.lower() not in {".png",".jpg",".txt"} for p in files):
        raise ValueError("Only research comparison images and license notices allowed")
    buffer=io.BytesIO()
    with tarfile.open(fileobj=buffer,mode="w:gz") as archive:
        for p in files: archive.add(p,arcname=str(p.relative_to(folder)))
    key=AESGCM.generate_key(bit_length=256);nonce=os.urandom(12)
    encrypted=AESGCM(key).encrypt(nonce,buffer.getvalue(),b"FilterSlap research visual audit v1")
    pub=serialization.load_pem_public_key(public_key.read_bytes())
    envelope=pub.encrypt(key,padding.OAEP(mgf=padding.MGF1(hashes.SHA256()),algorithm=hashes.SHA256(),label=None))
    output.mkdir(parents=True,exist_ok=True)
    (output/"private-visuals.aesgcm").write_bytes(encrypted)
    (output/"envelope.json").write_text(json.dumps({"key":envelope.hex(),"nonce":nonce.hex(),
        "aad":"FilterSlap research visual audit v1","format":"tar.gz"},indent=2)+"\n")


if __name__=="__main__":
    p=argparse.ArgumentParser()
    for n in ("folder","public-key","output"):p.add_argument("--"+n,type=Path,required=True)
    a=p.parse_args();seal(a.folder,a.public_key,a.output)
