package com.example.engine.v9

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class SpatialDodgeBurnTest {
    private fun gray(v:Int)=(255 shl 24) or (v shl 16) or (v shl 8) or v

    @Test fun signedLocalEditsProtectExtremeLuminance() {
        val pixels=IntArray(64*64) { index ->
            val x=index%64
            gray(if(x<32) 57 else 195)
        }
        val field=SpatialIllumination.build(pixels,64,64,0f)
        assertEquals(0f,field.depthAt(.2f,.5f,.02f,.9f),0f)
        assertEquals(0f,field.depthAt(.8f,.5f,.98f,.9f),0f)
        assertEquals(0f,field.depthAt(.5f,.5f,.5f,0f),0f)
        for (y in 0..8) {
            val yy=(y+.5f)/9f
            for(x in 0..8) {
                val xx=(x+.5f)/9f
                val p=field.depthAt(xx,yy,if(x<4) .22f else .77f,.85f)
                assertTrue(p.isFinite())
                assertTrue(p>=-.05201f && p<=.08701f)
            }
        }
    }

    @Test fun localEditsRespondToSourceIlluminationRatherThanConstantLook() {
        val pixels=IntArray(128*96) { i ->
            val x=i%128
            val y=i/128
            val level=if(x<64) 50+y/12 else 172+y/10
            gray(level.coerceIn(0,255))
        }
        val field=SpatialIllumination.build(pixels,128,96,0f)
        val dark=field.depthAt(.25f,.5f,.21f,1f)
        val bright=field.depthAt(.75f,.5f,.70f,1f)
        assertTrue("dark regions must not be burned",dark>=0f)
        assertTrue("bright regions must not be lifted",bright<=0f)
        assertTrue("some spatial edit should happen",abs(dark)+abs(bright)>0.00001f)
    }
}
