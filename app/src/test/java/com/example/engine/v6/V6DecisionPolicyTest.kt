package com.example.engine.v6

import org.junit.Assert.*
import org.junit.Test

class V6DecisionPolicyTest {
    private val a = IntArray(128) { 0xff777777.toInt() }
    @Test fun `already good images preserve original`() {
        val b = IntArray(a.size) { 0xff999999.toInt() }
        assertFalse(V6DecisionPolicy.decide(a,b,0f,false).accepted)
    }
    @Test fun `identity cannot masquerade as improvement`() {
        assertFalse(V6DecisionPolicy.decide(a,a,0f,true).accepted)
    }
    @Test fun `large out of gamut prediction rejects`() {
        val b = IntArray(a.size) { 0xff808080.toInt() }
        assertFalse(V6DecisionPolicy.decide(a,b,0.2f,true).accepted)
    }
    @Test fun `excessive full frame edits reject`() {
        val b = IntArray(a.size) { 0xff222222.toInt() }
        assertFalse(V6DecisionPolicy.decide(a,b,0f,true).accepted)
    }
    @Test fun `bounded natural shift remains eligible`() {
        val b = IntArray(a.size) { 0xff808080.toInt() }
        assertTrue(V6DecisionPolicy.decide(a,b,0f,true).accepted)
    }
    @Test fun `clipping must not rise significantly`() {
        val b = IntArray(a.size) { i -> if (i<20) 0xffffffff.toInt() else 0xff777777.toInt() }
        assertFalse(V6DecisionPolicy.decide(a,b,0f,true).accepted)
    }
}
