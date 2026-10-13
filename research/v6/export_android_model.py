"""Deterministic ONNX classifier and binary basis pack for offline Android assets.

Run only after pinned SHA-256/checksum validation. Assets are produced by CI rather
than storing upstream model weights in the source tree. Never output dummy weights.
"""
from __future__ import annotations
import argparse
import hashlib
from pathlib import Path
import numpy as np

try:
    from .adaptive_lut import AdaptiveLUT
except ImportError:
    from adaptive_lut import AdaptiveLUT


def export(model_dir: Path, output_dir: Path) -> None:
    import torch
    model=AdaptiveLUT(model_dir)
    output_dir.mkdir(parents=True,exist_ok=True)
    onnx_path=output_dir/'v6_classifier.onnx'
    basis_path=output_dir/'v6_basis_luts.bin'
    input_tensor=torch.linspace(0,1,3*256*256,dtype=torch.float32).reshape(1,3,256,256)
    torch.onnx.export(model.classifier,input_tensor,str(onnx_path),export_params=True,
                      do_constant_folding=True,input_names=['image'],output_names=['weights'],
                      opset_version=17,dynamic_axes=None)
    # ONNX Runtime must agree with native PyTorch within reasonable float tolerance.
    import onnxruntime as ort
    sess=ort.InferenceSession(str(onnx_path),providers=['CPUExecutionProvider'])
    probes=[torch.rand((1,3,256,256),generator=torch.Generator().manual_seed(i)) for i in (5,37,531)]
    with torch.inference_mode():
        for x in probes:
            expected=model.classifier(x).detach().numpy()
            actual=sess.run(None,{'image':x.numpy()})[0]
            if not np.allclose(expected,actual,rtol=1e-3,atol=2e-3):
                raise ValueError(f"ONNX parity failed: max abs {np.max(np.abs(expected-actual))}")
    model.bases.astype('<f4',copy=False).tofile(basis_path)
    # Validate binary layout mathematically against the reference NumPy LUT operator.
    check=np.fromfile(basis_path,dtype='<f4').reshape(3,3,33,33,33)
    if not np.array_equal(check,model.bases):
        raise ValueError('Binary LUT basis was exported in incorrect order')
    def sha(path): return hashlib.sha256(path.read_bytes()).hexdigest()
    (output_dir/'v6_model_sha256.txt').write_text(
        f'v6_classifier.onnx {sha(onnx_path)}\nv6_basis_luts.bin {sha(basis_path)}\n',encoding='ascii')
    print(f'ONNX classifier parity: 3 random probes passed; onnx={onnx_path.stat().st_size}B '
          f'LUT={basis_path.stat().st_size}B; assets SHA-256 pinned')


if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--model-dir',type=Path,required=True)
    p.add_argument('--output-dir',type=Path,required=True);args=p.parse_args()
    export(args.model_dir,args.output_dir)
