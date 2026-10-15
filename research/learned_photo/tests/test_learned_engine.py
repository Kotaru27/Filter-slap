"""Smoke tests are algorithm invariants, NOT photo preference evidence."""
from pathlib import Path
import csv
import json
import sys
import tempfile
import unittest
import numpy as np
from PIL import Image
import torch
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from model import LearnedPhotoEditor,objective
from data import read_manifest
from train import train
from evaluate import load,render,evaluate,blind_cards,score_votes

class TestLearnedEngine(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        torch.set_num_threads(2)

    def test_untrained_edit_is_exact_identity_and_tiling_has_no_seams(self):
        image=torch.rand(1,3,45,71)
        for mode in ("global","spatial"):
            model=LearnedPhotoEditor(mode)
            self.assertTrue(torch.equal(image,model(image)))
            self.assertTrue(torch.allclose(model(image),model(image,tile_rows=9),atol=1e-6))

    def test_nontrivial_guided_edit_bounded_and_tiling_equivalent(self):
        source=torch.rand(1,3,73,99)
        model=LearnedPhotoEditor("spatial")
        with torch.no_grad():
            model.head.bias.fill_(2.)
            whole=model(source)
            tiled=model(source,tile_rows=13)
        self.assertLess(float((whole-tiled).abs().max()),1e-6)
        self.assertGreaterEqual(float(whole.min()),0)
        self.assertLessEqual(float(whole.max()),1)
        self.assertLessEqual(float((whole-source).abs().max()),.35+1e-6)

    def test_model_trains_with_nonzero_parameter_gradients(self):
        model=LearnedPhotoEditor("spatial")
        source=torch.rand(1,3,28,43)
        goal=(source*.82+.06).clamp(0,1)
        result=model(source)
        loss,_=objective(source,result,goal,torch.zeros(1))
        loss.backward()
        self.assertGreater(float(model.head.weight.grad.abs().sum()),0)

    def test_rejects_invalid_images(self):
        with self.assertRaises(ValueError):
            LearnedPhotoEditor()(torch.full((1,3,16,16),float("nan")))

class TestFullWorkflow(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root=Path(self.temp.name)
        self.csv=self.root/"pairs.csv"
        self.rows=[]
        for i,split in enumerate(["train","train","val","test"]):
            rng=np.random.default_rng(57+i)
            p=rng.integers(40,198,(64,72,3),dtype=np.uint8)
            edited=np.clip(p.astype(np.int16)+8,0,255).astype(np.uint8)
            Image.fromarray(p).save(self.root/f"input{i}.png")
            Image.fromarray(edited).save(self.root/f"edited{i}.png")
            self.rows.append({"id":f"case{i}","source":f"input{i}.png",
                "target":f"edited{i}.png","rights":"licensed-for-research-unit-test",
                "split":split,"group":f"capture{i}","category":"test",
                "preserve":"no"})
        self.write()

    def write(self):
        with self.csv.open("w",newline="") as f:
            writer=csv.DictWriter(f,fieldnames=list(self.rows[0]))
            writer.writeheader();writer.writerows(self.rows)

    def test_split_and_rights_guards(self):
        self.assertEqual(len(read_manifest(self.csv)),4)
        self.rows[3]["group"]="capture0"
        self.write()
        with self.assertRaisesRegex(ValueError,"group leakage"):
            read_manifest(self.csv)
        self.rows[3]["group"]="capture3"
        self.rows[3]["rights"]="unknown"
        self.write()
        with self.assertRaisesRegex(ValueError,"rights"):
            read_manifest(self.csv)

    def test_cross_split_identical_source_rejected(self):
        self.rows[3]["source"]="input0.png"
        self.write()
        with self.assertRaisesRegex(ValueError,"leak"):
            read_manifest(self.csv)

    def test_real_data_required_for_production_training_defaults(self):
        with self.assertRaisesRegex(ValueError,"insufficient"):
            train(self.csv,self.root/"model","spatial",epochs=1)

    def test_smoke_training_evaluation_and_human_review_integrity(self):
        summary=train(self.csv,self.root/"model","global",epochs=1,
                      batch=2,min_train=1,min_val=1,min_test=1)
        self.assertTrue(summary["quality_not_verified_by_human_review"])
        checkpoint=self.root/"model"/"best.pt"
        output=render(load(checkpoint),Image.open(self.root/"input3.png"),tile_rows=11)
        self.assertEqual(output.size,(72,64))
        predicted=self.root/"predictions"
        results=evaluate(self.csv,checkpoint,predicted,"test",min_test=1)
        self.assertFalse(results["release_authorized"])
        baseline=self.root/"baseline";baseline.mkdir()
        Image.open(self.root/"input3.png").save(baseline/"case3.png")
        cards=self.root/"cards";key=self.root/"secret.json"
        self.assertEqual(blind_cards(self.csv,predicted,baseline,cards,key,min_test=1),1)
        self.assertTrue((cards/"case3.jpg").exists())
        self.assertFalse((cards/"secret.json").exists())
        side=json.loads(key.read_text())[0]["challenger_side"]
        votes=self.root/"votes.csv"
        with votes.open("w",newline="") as f:
            writer=csv.writer(f)
            writer.writerow(["id","reviewer","preference","serious_harm_side","notes"])
            for reviewer in ("a","b","c"):
                writer.writerow(["case3",reviewer,side,"NONE",""])
        score=score_votes(key,votes,self.root/"score.json",min_cases=1,min_reviewers=3)
        self.assertEqual(score["challenger_preference"],1.)
        self.assertFalse(score["automatic_release_approval"])

if __name__=="__main__":
    unittest.main()
