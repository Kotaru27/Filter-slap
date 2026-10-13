import sys
import unittest
from pathlib import Path

import numpy as np
from PIL import Image, ImageCms

sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from adaptive_lut import identity_lut,interpolate_lut,to_srgb
from benchmark import stats


class LUTMath(unittest.TestCase):
    def test_identity_random(self):
        rng=np.random.default_rng(12)
        x=rng.random((37,49,3),dtype=np.float32)
        for dim in (2,7,33):
            got=interpolate_lut(x,identity_lut(dim),chunk_pixels=17)
            self.assertLess(np.max(np.abs(got-x)),2.e-6)
    def test_channel_axis_is_rgb_not_bgr(self):
        lut=identity_lut(7)
        # Intentionally alter RED output as nonlinear function of BLUE.
        lut[0]=lut[0]+0.13*lut[2]**2
        sample=np.array([[[.1,.2,.7],[.8,.4,.05]]],dtype=np.float32)
        y=interpolate_lut(sample,lut)
        self.assertAlmostEqual(float(y[0,0,0]),.1+.13*.7**2,delta=0.001)
        self.assertAlmostEqual(float(y[0,0,1]),.2,places=5)
        self.assertAlmostEqual(float(y[0,0,2]),.7,places=5)
    def test_endpoints_and_small_chunks(self):
        x=np.array([[[0.,0.,0.],[1.,1.,1.],[1.,0.,0.],[0.,1.,0.],[0.,0.,1.]]],np.float32)
        y=interpolate_lut(x,identity_lut(),chunk_pixels=1)
        np.testing.assert_allclose(x,y,atol=1.e-6)
    def test_constant_value_lut(self):
        lut=np.zeros((3,4,4,4),np.float32)
        lut[0]+=.123;lut[1]+=.432;lut[2]+=.789
        sample=np.ones((3,4,3),np.float32)/2
        y=interpolate_lut(sample,lut,chunk_pixels=5)
        np.testing.assert_allclose(y,np.broadcast_to(np.array([.123,.432,.789]),y.shape),atol=1.e-6)
    def test_no_silent_rgb_clamp(self):
        lut=identity_lut(3)
        lut*=1.5
        sample=np.ones((1,1,3),np.float32)
        out=interpolate_lut(sample,lut)
        np.testing.assert_allclose(out,np.ones((1,1,3))*1.5,atol=1.e-6)
    def test_reject_nan(self):
        with self.assertRaises(ValueError):interpolate_lut(np.full((1,1,3),np.nan,dtype=np.float32),identity_lut())
    def test_embedded_srgb_icc_roundtrip(self):
        im=Image.new("RGB",(4,5),(30,120,180))
        profile=ImageCms.ImageCmsProfile(ImageCms.createProfile("sRGB"))
        im.info["icc_profile"]=profile.tobytes()
        converted=to_srgb(im)
        self.assertEqual(converted.size,im.size)
        np.testing.assert_allclose(np.asarray(converted),np.asarray(im),atol=2)
    def test_bad_icc_fails_closed(self):
        im=Image.new("RGB",(4,5),(30,120,180))
        im.info["icc_profile"]=b"not-a-valid-icc"
        with self.assertRaises(ValueError):to_srgb(im)
    def test_size_mismatch_metrics(self):
        with self.assertRaises(ValueError):stats(Image.new("RGB",(5,7)),Image.new("RGB",(5,8)))


if __name__=="__main__":unittest.main()
