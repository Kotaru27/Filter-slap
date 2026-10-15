import csv
import tempfile
import unittest
from pathlib import Path
import sys
import numpy as np
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from regression_audit import damage, safe, attenuate, measures, decision_map, lab
from prepare_audit_cohort import assert_disjoint


class RegressionAuditTest(unittest.TestCase):
    def test_safe_can_be_far_worse_than_identity_or_expert(self):
        source=np.full((8,9,3),80,dtype=np.float32)
        output=source+30
        self.assertTrue(safe(damage(source,output)))
        self.assertEqual(measures(source,output,source)["rgb_l1"],30.)

    def test_new_clipping_not_cancelled_by_recovered_highlights(self):
        source=np.array([[[240,240,240],[255,255,255]]],dtype=np.float32)
        output=np.array([[[255,255,255],[240,240,240]]],dtype=np.float32)
        self.assertEqual(damage(source,output)["new_clipping"],.5)
        self.assertFalse(safe(damage(source,output)))

    def test_android_byte_rounding_and_source_ownership(self):
        source=np.array([[[80,80,80],[83,83,83]]],dtype=np.float32)
        output=source+1
        before=source.copy()
        np.testing.assert_array_equal(attenuate(source,output,.5),output)
        np.testing.assert_array_equal(attenuate(source,output,0),source)
        np.testing.assert_array_equal(source,before)

    def test_lab_neutrals_and_missing_shadow_region(self):
        white=np.full((3,4,3),255,dtype=np.float32)
        np.testing.assert_allclose(lab(white)[0,0],[100,0,0],atol=1e-3)
        m=measures(white,white,white)
        self.assertEqual(m["rgb_l1"],0.)
        self.assertIsNone(m["dark_lift"])

    def test_duplicate_or_missing_native_decisions_fail(self):
        with tempfile.TemporaryDirectory() as d:
            p=Path(d)/"decisions.csv"
            p.write_text("id,decision\na,accepted\na,accepted\n")
            with self.assertRaises(ValueError):decision_map(p,["a","b"])
            p.write_text("id,decision\na,accepted\n")
            with self.assertRaises(ValueError):decision_map(p,["a","b"])

    def test_holdout_requires_capture_level_disjointness(self):
        assert_disjoint(["fresh"],["old"])
        with self.assertRaises(ValueError):assert_disjoint(["old"],["old"])
        with self.assertRaises(ValueError):assert_disjoint(["fresh","fresh"],["old"])


if __name__=="__main__":unittest.main()
