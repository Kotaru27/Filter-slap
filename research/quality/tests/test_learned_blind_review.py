import csv
import json
import sys
import tempfile
import unittest
from pathlib import Path
from PIL import Image

sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from learned_blind_review import create,score


class BlindComparisonProtocolTest(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        root=Path(self.temp.name)
        self.originals=root/"original"
        self.baseline=root/"baseline"
        self.candidate=root/"candidate"
        self.cards=root/"cards"
        self.key=root/"secret"/"key.json"
        for x in (self.originals,self.baseline,self.candidate):x.mkdir()
        for i in range(3):
            for dir_,color in ((self.originals,(20,80,120)),
                               (self.baseline,(40,75,119)),
                               (self.candidate,(41,77,121))):
                Image.new("RGB",(32+i,40),color).save(dir_/(f"photo_{i}.png"))

    def test_blind_sides_and_votes_are_consistent_and_independent(self):
        self.assertEqual(3,create(self.originals,self.baseline,self.candidate,self.cards,self.key))
        cases=json.loads(self.key.read_text())
        self.assertEqual(3,len(cases))
        for row in cases:
            self.assertIn(row["candidate_side"],("A","B"))
            self.assertTrue((self.cards/(row["id"]+".jpg")).is_file())
        self.assertTrue((self.cards/"votes_template.csv").is_file())
        self.assertFalse((self.cards/"key.json").exists())
        votes=self.cards/"votes.csv"
        with votes.open("w",newline="") as f:
            w=csv.writer(f)
            w.writerow(["id","reviewer","preference","serious_damage_side","notes"])
            for row in cases:
                for reviewer in ("one","two","three"):
                    choice=row["candidate_side"] if reviewer!="three" else (
                        "B" if row["candidate_side"]=="A" else "A")
                    w.writerow([row["id"],reviewer,choice,"NONE",""])
        result=score(self.key,votes,self.cards/"result.json",3,3)
        self.assertEqual(3,result["candidate_preferred_cases"])
        self.assertEqual(0,result["candidate_serious_damage_cases"])
        self.assertFalse(result["no_automatic_release_approval"] is False)
        with self.assertRaises(FileExistsError):
            create(self.originals,self.baseline,self.candidate,self.cards,self.key)

    def test_rejects_mismatched_original_and_output_geometry(self):
        Image.new("RGB",(1,1)).save(self.candidate/"photo_0.png")
        with self.assertRaisesRegex(ValueError,"geometry"):
            create(self.originals,self.baseline,self.candidate,self.cards,self.key)


if __name__=="__main__":
    unittest.main()
