"""A CI integration smoke check of the *real* upstream pretrained model.

Runs synthetic inputs only to verify weights, graph, LUT numerical safety and
that at least some content-dependent coefficients are observable. It is NOT a
photographic quality evaluation, and must not be promoted as one.
"""
import argparse
import json
from pathlib import Path
import numpy as np
from PIL import Image

try:
    from .adaptive_lut import AdaptiveLUT
except ImportError:
    from adaptive_lut import AdaptiveLUT


def main():
    parser=argparse.ArgumentParser()
    parser.add_argument("--model-dir",type=Path,required=True)
    parser.add_argument("--output",type=Path,required=True)
    args=parser.parse_args()
    rng=np.random.default_rng(42537)
    cases={
        "bright_neutral":np.full((80,120,3),210,dtype=np.uint8),
        "dark_neutral":np.full((80,120,3),35,dtype=np.uint8),
        "color_checker_noise":rng.integers(0,256,(80,120,3),dtype=np.uint8),
    }
    model=AdaptiveLUT(args.model_dir)
    results=[]
    for label,data in cases.items():
        result=model.enhance(Image.fromarray(data,"RGB"))
        out=np.asarray(result.output)
        assert out.shape==data.shape and np.isfinite(out).all()
        assert np.isfinite(result.coefficients).all() and np.isfinite(result.out_of_range_fraction)
        results.append({"case":label,"coefficients":result.coefficients,
                        "mean_source":float(data.mean()),"mean_result":float(out.mean()),
                        "elapsed_ms_CPU":round(result.elapsed_ms,2),
                        "out_of_range_fraction":result.out_of_range_fraction,
                        "model_sha256":result.model_sha256})
    distinct=max(np.max(np.abs(np.array(a['coefficients'])-np.array(b['coefficients'])))
                 for a in results for b in results)
    # Strict non-collapse test for three different synthetic inputs; NOT an aesthetic gate.
    if distinct<0.000001:
        raise AssertionError("Classifier coefficients appear identical across three synthetic inputs")
    args.output.parent.mkdir(parents=True,exist_ok=True)
    args.output.write_text(json.dumps({"synthetic_smoke_not_quality":True,"results":results,
                                       "coefficient_range":float(distinct)},indent=2),encoding="utf-8")
    print(json.dumps({"cases":len(results),"coefficient_range":float(distinct),"all_passed":True}))

if __name__=="__main__":main()
