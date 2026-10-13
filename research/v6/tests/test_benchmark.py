import csv
import json
import sys
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace

import numpy as np
from PIL import Image

sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from benchmark import load_manifest,blind,score


class ManifestSafety(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory()
        self.root=Path(self.tmp.name)
        Image.fromarray(np.full((8,10,3),150,dtype=np.uint8)).save(self.root/'original.png')
        Image.fromarray(np.full((8,10,3),160,dtype=np.uint8)).save(self.root/'v5.png')
        Image.fromarray(np.full((8,10,3),170,dtype=np.uint8)).save(self.root/'a.png')
        self.manifest=self.root/'manifest.csv'
    def tearDown(self):self.tmp.cleanup()
    def make(self,rows):
        with self.manifest.open('w',newline='') as f:
            w=csv.writer(f);w.writerow(['id','category','original','v5','rights','split','group']);w.writerows(rows)
    def row(self,id='a',rights='owned',split='test',group='g'):
        return [id,'landscape','original.png','v5.png',rights,split,group]
    def test_rights_required(self):
        self.make([self.row(rights='unknown')])
        with self.assertRaises(ValueError):load_manifest(self.manifest)
    def test_holdout_groups_cannot_leak(self):
        self.make([self.row(),self.row('b',split='training')])
        with self.assertRaises(ValueError):load_manifest(self.manifest)
    def test_minimum_test_count_enforced(self):
        self.make([self.row()])
        with self.assertRaisesRegex(ValueError,'require 200'):load_manifest(self.manifest,min_test=200)
    def test_fail_missing_v5(self):
        self.make([self.row()]);(self.root/'v5.png').unlink()
        with self.assertRaises(FileNotFoundError):load_manifest(self.manifest,require_v5=True)
    def test_blind_cards_and_votes_no_mapping_leak(self):
        self.make([self.row()])
        args=SimpleNamespace(manifest=self.manifest,learned_dir=self.root,out=self.root/'cards',key=self.root/'private.json',min_test=1,control="v5")
        blind(args)
        self.assertTrue((args.out/'a.jpg').is_file())
        self.assertTrue(args.key.is_file())
        self.assertFalse((args.out/'private.json').exists())
        secret=json.loads(args.key.read_text())[0]
        vote=self.root/'votes.csv'
        vote.write_text('id,reviewer,choice,harm_side,notes\na,rev1,'+secret['learned_side']+',NONE,nice\n')
        result=self.root/'summary.json'
        score(SimpleNamespace(key=args.key,votes=vote,out=result))
        parsed=json.loads(result.read_text())
        self.assertEqual(parsed['learned_wins'],1)
        self.assertEqual(parsed['decisive_votes'],1)
        with self.assertRaises(FileExistsError):blind(args)

if __name__=='__main__':unittest.main()
