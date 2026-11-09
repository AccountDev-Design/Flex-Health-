package com.flexhealth.heartmonitor.wear.service

import org.junit.Assert.assertEquals
import org.junit.Test

class ReadingGateTest {
    @Test fun duplicatesAndOutOfOrderReadingsAreDroppedAtSource() {
        val g = ReadingGate()
        val ts = listOf(1_000L, 2_000L, 2_000L, 1_500L, 3_000L, 3_000L, 4_000L)
        assertEquals(listOf(true, true, false, false, true, false, true), ts.map { g.accept(it) })
    }
}
