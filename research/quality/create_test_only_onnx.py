#!/usr/bin/env python3
"""Generate test-only ONNX models from known synthetic coefficient fields.

These are intentionally NOT trained, NOT photo-quality models, and must never
be packaged inside a user's APK. The image-size/ONNX-interface contract and
the runtime's SHA pinning are what this research fixture verifies.
"""
import argparse
import hashlib
import json
from pathlib import Path
import numpy as np
import onnx
from onnx import TensorProto, helper, numpy_helper, checker

def make(out:Path,mode:str):
    side=1 if mode=="global" else 16
    grid=np.zeros((1,12,8,side,side),dtype=np.float32)
    for rgb_channel in range(3):
        bias_channel=rgb_channel*4+3
        for z in range(8):
            for y in range(side):
                for x in range(side):
                    if mode=="global":
                        bias=.08
                    else:
                        bias=.025+.055*(y/(side-1))+.015*(x/(side-1))+.010*(z/7)
                    grid[0,bias_channel,z,y,x]=bias
    consts=[
        numpy_helper.from_array(grid,"known_output_coefficients"),
        numpy_helper.from_array(np.array([1,1,1,1,1],dtype=np.int64),"reshape_dims"),
        numpy_helper.from_array(np.array([1.e-7],dtype=np.float32),"nonzero_scale")
    ]
    nodes=[
        helper.make_node("ReduceMean",["srgb_context"],["input_mean"],keepdims=1),
        helper.make_node("Reshape",["input_mean","reshape_dims"],["reduced"]),
        helper.make_node("Mul",["reduced","nonzero_scale"],["perturbation"]),
        helper.make_node("Add",["known_output_coefficients","perturbation"],["guided_affine_grid"])
    ]
    graph=helper.make_graph(nodes,"synthetic-fixture-"+mode,
        [helper.make_tensor_value_info("srgb_context",TensorProto.FLOAT,[1,3,256,256])],
        [helper.make_tensor_value_info("guided_affine_grid",TensorProto.FLOAT,[1,12,8,side,side])],
        initializer=consts)
    model=helper.make_model(graph,producer_name="filter-slap-test-only",
        opset_imports=[helper.make_opsetid("",17)])
    model.ir_version=9
    checker.check_model(model)
    dest=out/("learned_"+mode+"_predictor.onnx")
    onnx.save(model,dest)
    return {"mode":mode,"sha256":hashlib.sha256(dest.read_bytes()).hexdigest(),
        "bytes":dest.stat().st_size,
        "trained":False,"rights_for_app":False,"test_only":True}

if __name__=="__main__":
    p=argparse.ArgumentParser()
    p.add_argument("--output",type=Path,required=True)
    a=p.parse_args()
    a.output.mkdir(parents=True,exist_ok=True)
    records=[make(a.output,mode) for mode in ("global","spatial")]
    (a.output/"fixtures.json").write_text(json.dumps(records,indent=2)+"\n")
    print(json.dumps(records,indent=2))
