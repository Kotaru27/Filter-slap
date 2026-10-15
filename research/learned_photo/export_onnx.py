"""Export only trained guided-grid predictor; require ONNX Runtime parity.

This exporter does not change Android or claim a complete photo editor.
Actual Android bilateral interpolation/pixel parity needs separate validation.
"""
import argparse
import hashlib
import json
from pathlib import Path
import numpy as np
import torch
try:
    from .evaluate import load
except ImportError:
    from evaluate import load

class Predictor(torch.nn.Module):
    def __init__(self,model):
        super().__init__()
        self.model=model

    def forward(self,input):
        return self.model.coefficients(input)

def export(checkpoint:Path,out:Path)->dict:
    try:
        import onnx
        import onnxruntime as ort
    except ImportError as exc:
        raise RuntimeError("onnx and onnxruntime required for conversion parity") from exc
    net=load(checkpoint)
    model=Predictor(net).eval()
    out.mkdir(parents=True,exist_ok=True)
    onnx_path=out/("learned_"+net.mode+"_predictor.onnx")
    sample=torch.rand((1,3,256,256),generator=torch.Generator().manual_seed(3))
    torch.onnx.export(model,sample,str(onnx_path),opset_version=17,dynamo=False,
                      do_constant_folding=True,input_names=["srgb_context"],
                      output_names=["guided_affine_grid"],export_params=True)
    onnx.checker.check_model(str(onnx_path))
    session=ort.InferenceSession(str(onnx_path),providers=["CPUExecutionProvider"])
    errors=[]
    with torch.inference_mode():
        for seed in (5,37,531):
            input=torch.rand((1,3,256,256),
                             generator=torch.Generator().manual_seed(seed))
            expected=model(input).numpy()
            actual=session.run(None,{"srgb_context":input.numpy()})[0]
            delta=float(np.max(np.abs(expected-actual)))
            errors.append(delta)
            if not np.allclose(expected,actual,atol=2e-4,rtol=2e-4):
                raise ValueError(f"ONNX coefficient mismatch: {delta}")
    report={"kind":"predictor_only_NOT_complete_Android_renderer",
            "file":onnx_path.name,"mode":net.mode,
            "sha256":hashlib.sha256(onnx_path.read_bytes()).hexdigest(),
            "max_coeff_error":max(errors),
            "android_pixel_parity_not_validated":True,
            "approved_for_app":False}
    (out/"export_manifest.json").write_text(json.dumps(report,indent=2)+"\n")
    return report

if __name__=="__main__":
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument("--checkpoint",type=Path,required=True)
    p.add_argument("--out",type=Path,required=True)
    args=p.parse_args()
    print(json.dumps(export(args.checkpoint,args.out),indent=2))
