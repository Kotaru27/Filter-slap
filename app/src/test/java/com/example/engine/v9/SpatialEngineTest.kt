package com.example.engine.v9

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

/** Synthetic invariants apply to any image geometry; never tuned to a named photo. */
class SpatialEngineTest {
    private fun gray(v:Int)=-0x1000000 or (v shl 16) or (v shl 8) or v
    @Test fun `edge guided recovery improves textured shadows without depressing bright regions`() {
        val w=144;val h=96
        val source=IntArray(w*h) { i ->
            val x=i%w; val y=i/w
            gray(if(x<w/2) 32+(x*17+y*11)%52 else 215+(x*11+y*17)%23)
        }
        val field=SpatialIllumination.build(source,w,h,1f)
        var shadow=0f;var bright=0f;var a=0;var b=0
        val edited=IntArray(w*h)
        for(i in source.indices){
            val x=i%w;val y=i/w;val v=source[i] and 255
            val lift=field.liftAt((x+.5f)/w,(y+.5f)/h,v/255f,.9f)
            edited[i]=gray((v+255*lift).toInt().coerceIn(0,255))
            if(x<w/2){shadow+=lift;a++}else{bright+=lift;b++}
        }
        assertTrue(shadow/a>.025f)
        assertTrue(bright/b<.002f)
        val q=SpatialQuality.compare(source,edited,w,h)
        assertTrue(q.recoveredShadow>.015f)
        assertTrue(q.brightDrift<.005f)
        assertTrue(q.evidenceTiles>0)
    }
    @Test fun `near black featureless images remain unchanged`() {
        val w=78;val h=62
        val src=IntArray(w*h){gray(2)}
        val f=SpatialIllumination.build(src,w,h,7f)
        assertEquals(0f,f.recoverableFraction,0f)
        for(y in 0 until h step 7)for(x in 0 until w step 9)
            assertEquals(0f,f.liftAt((x+.5f)/w,(y+.5f)/h,2f/255f,1f),0f)
    }
    @Test fun `spatial field output is finite and bounded for varied frame sizes`() {
        for((w,h) in listOf(1 to 1,7 to 81,129 to 75,200 to 200)) {
            val src=IntArray(w*h){i->gray((i*1777+37) and 255)}
            val f=SpatialIllumination.build(src,w,h,2f)
            assertTrue(f.recoverableFraction in 0f..1f)
            for(y in 0..16)for(x in 0..16){
                val value=f.liftAt(x/16f,y/16f,.17f,1f)
                assertTrue("lift=$value",value.isFinite() && value in 0f.. .19f)
            }
        }
    }
    @Test fun `pure highlight modifications receive no shadow credit`() {
        val w=96;val h=96
        val source=IntArray(w*h){i->gray(if(i%w<48) 37+(i*17)%55 else 196+(i*13)%44)}
        val modified=IntArray(w*h){i->
            val v=source[i] and 255
            gray(if(i%w<48)v else (v-34).coerceAtLeast(0))
        }
        val q=SpatialQuality.compare(source,modified,w,h)
        assertEquals(0f,q.recoveredShadow,0.00001f)
        assertTrue(q.brightDrift>0.02f)
    }
    @Test fun `source-defined spatial quality score rejects disappearing texture`() {
        val w=100;val h=100
        val src=IntArray(w*h){i->gray(35+(i%2)*47)}
        val flat=IntArray(w*h){gray(64)}
        val q=SpatialQuality.compare(src,flat,w,h)
        assertTrue(q.detailLoss>0f)
    }
}