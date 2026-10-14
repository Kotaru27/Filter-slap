package com.example.engine.v9

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Spatial evidence, independent of scene identity and independent of the candidate's recipe. */
data class SpatialQuality(
    val recoveredShadow: Float,
    val brightDrift: Float,
    val detailLoss: Float,
    val haloRisk: Float,
    val evidenceTiles: Int
) {
    companion object {
        /**
         * Source-defined grid, re-used for every candidate. Shadow credit only
         * exists where the original actually contains non-trivial texture.
         * A uniformly black patch supplies no evidence of recoverable detail.
         */
        fun compare(original:IntArray,edited:IntArray,width:Int,height:Int):SpatialQuality {
            require(width>0 && height>0 && original.size==width*height && edited.size==original.size)
            val cellsX=min(12,max(1,width/12))
            val cellsY=min(12,max(1,height/12))
            var recovered=0f;var drift=0f;var lost=0f;var halo=0f
            var shadows=0;var bright=0;var pairs=0
            val originalMeans=FloatArray(cellsX*cellsY)
            val editedMeans=FloatArray(cellsX*cellsY)
            val shadowWeights=FloatArray(cellsX*cellsY)
            for (cy in 0 until cellsY) for(cx in 0 until cellsX){
                val index=cy*cellsX+cx
                val x0=cx*width/cellsX;val x1=(cx+1)*width/cellsX
                val y0=cy*height/cellsY;val y1=(cy+1)*height/cellsY
                var origSum=0.0;var outSum=0.0
                var origSquare=0.0;var outSquare=0.0
                var count=0
                for(y in y0 until y1) for(x in x0 until x1){
                    val i=y*width+x
                    val yBefore=luma(original[i]).toDouble();val yAfter=luma(edited[i]).toDouble()
                    origSum+=yBefore;outSum+=yAfter
                    origSquare+=yBefore*yBefore;outSquare+=yAfter*yAfter
                    count++
                }
                val before=(origSum/count).toFloat();val after=(outSum/count).toFloat()
                val stdBefore=kotlin.math.sqrt(max(0.0,origSquare/count-before*before)).toFloat()
                val stdAfter=kotlin.math.sqrt(max(0.0,outSquare/count-after*after)).toFloat()
                originalMeans[index]=before;editedMeans[index]=after
                if(before in .045f.. .37f && stdBefore>.018f){
                    val recoverable=(stdBefore/(stdBefore+.045f)).coerceIn(0f,1f)
                    shadowWeights[index]=recoverable
                    // Credit improvement only toward a useful, moderate luminance;
                    // overshooting that level is not extra credit.
                    val beforeError=abs(.36f-before)
                    val afterError=abs(.36f-after)
                    recovered+=(beforeError-afterError)*recoverable
                    // Subject texture that disappears after smoothing is not recovery.
                    lost+=(max(0f,stdBefore*.72f-stdAfter))*recoverable
                    shadows++
                }
                if(before>.72f){
                    // Penalize avoidable motion of originally well-exposed bright regions.
                    drift+=max(0f,abs(after-before)-.025f)
                    bright++
                }
            }
            // Excess discontinuity added by the edit across source-defined tile boundaries.
            for(y in 0 until cellsY)for(x in 0 until cellsX){
                val k=y*cellsX+x
                if(x+1<cellsX){
                    val q=k+1
                    val sourceJump=originalMeans[k]-originalMeans[q]
                    val finalJump=editedMeans[k]-editedMeans[q]
                    halo+=max(0f,abs(finalJump-sourceJump)-.04f)
                    pairs++
                }
                if(y+1<cellsY){
                    val q=k+cellsX
                    val sourceJump=originalMeans[k]-originalMeans[q]
                    val finalJump=editedMeans[k]-editedMeans[q]
                    halo+=max(0f,abs(finalJump-sourceJump)-.04f)
                    pairs++
                }
            }
            return SpatialQuality(
                if(shadows>0)recovered/shadows else 0f,
                if(bright>0)drift/bright else 0f,
                if(shadows>0)lost/shadows else 0f,
                if(pairs>0)halo/pairs else 0f,
                shadows
            )
        }
        private fun luma(pixel:Int):Float =
            ((pixel ushr 16 and 255)*.2126f+(pixel ushr 8 and 255)*.7152f+(pixel and 255)*.0722f)/255f
    }
}