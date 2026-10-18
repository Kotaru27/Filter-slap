package com.example.engine.v14

import com.example.engine.v7.DamageAccumulator
import org.junit.Assert.*
import org.junit.Test

class PhotographicDamageGateTest {
    private fun rgb(r:Int,g:Int,b:Int)=(255 shl 24) or
        (r shl 16) or (g shl 8) or b

    @Test fun ordinaryBrighteningMustNotMasqueradeAsNewSaturation() {
        val damage=DamageAccumulator()
        repeat(3000) {
            damage.add(rgb(150,62,15),rgb(195,81,20))
        }
        assertEquals(0f,damage.report().vividBoosted,0f)
    }

    @Test fun trueOversaturationStillCountsAsDamage() {
        val damage=DamageAccumulator()
        repeat(3000) {
            damage.add(rgb(155,83,42),rgb(210,56,16))
        }
        assertEquals(1f,damage.report().vividBoosted,0f)
    }

    @Test fun newClippingAndCrushedShadowsRemainProtected() {
        val clip=DamageAccumulator()
        val crushed=DamageAccumulator()
        repeat(500) {
            clip.add(rgb(180,171,165),rgb(255,189,182))
            crushed.add(rgb(21,20,17),rgb(2,2,2))
        }
        assertTrue(clip.report().newlyClipped>0f)
        assertTrue(crushed.report().newlyCrushed>0f)
        assertFalse(clip.report().safe)
        assertFalse(crushed.report().safe)
    }
}
