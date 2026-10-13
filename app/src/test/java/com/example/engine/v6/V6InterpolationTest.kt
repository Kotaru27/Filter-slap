package com.example.engine.v6

import org.junit.Assert.assertEquals
import org.junit.Test

class V6InterpolationTest {
    @Test fun `identity LUT reproduces arbitrary colors`() {
        val dim=33
        val lut=FloatArray(3*dim*dim*dim)
        for(b in 0 until dim)for(g in 0 until dim)for(r in 0 until dim){
            val idx=(b*dim+g)*dim+r
            lut[idx]=r/32f;lut[dim*dim*dim+idx]=g/32f;lut[2*dim*dim*dim+idx]=b/32f
        }
        for(i in 0 until 101){
            val r=i/100f;val g=(100-i)/100f;val b=(i*13%101)/100f
            val output=V6AdaptiveEditor.sample(lut,r,g,b)
            assertEquals(r,output[0],0.000001f)
            assertEquals(g,output[1],0.000001f)
            assertEquals(b,output[2],0.000001f)
        }
    }
}
