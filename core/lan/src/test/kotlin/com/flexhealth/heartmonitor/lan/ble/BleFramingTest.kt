package com.flexhealth.heartmonitor.lan.ble

import com.flexhealth.heartmonitor.protocol.ProtocolConstants
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.random.Random

class BleFramingTest {
    @Test fun roundTripForAllMtusAndSizes() {
        for (mtu in listOf(23, 64, 185, 247, 517)) {
            for (size in listOf(1, BleFraming.maxPayload(mtu), BleFraming.maxPayload(mtu) + 1, 431, ProtocolConstants.LAN_MAX_FRAME_BYTES)) {
                val msg = Random(size + mtu).nextBytes(size)
                val chunks = BleFraming.fragment(msg, mtu, messageId = size)
                assertTrue("cada fragmento cabe en la MTU", chunks.all { it.size <= mtu - 3 })
                val r = BleFraming.Reassembler()
                val out = chunks.map { r.accept(it) }
                out.dropLast(1).forEach { assertNull(it) }
                assertArrayEquals("mtu=$mtu size=$size", msg, out.last())
            }
        }
    }

    @Test fun lostFragmentResynchronisesOnNextMessage() {
        val r = BleFraming.Reassembler()
        val a = BleFraming.fragment(ByteArray(100) { 1 }, 23, 1)
        val b = BleFraming.fragment(ByteArray(50) { 2 }, 23, 2)
        a.dropLast(1).forEach { r.accept(it) } // se pierde el último fragmento de A
        val out = b.map { r.accept(it) }.last()
        assertArrayEquals(ByteArray(50) { 2 }, out)
        assertEquals(1, r.discarded)
    }

    @Test fun oversizedAndEmptyFragmentsAreRejected() {
        val r = BleFraming.Reassembler(maxBytes = 64)
        val chunks = BleFraming.fragment(ByteArray(100), 23, 3)
        try { chunks.forEach { r.accept(it) }; fail() } catch (e: BleFramingException) { /* ok */ }
        try { r.accept(byteArrayOf(0x80.toByte())); fail() } catch (e: BleFramingException) { /* ok */ }
        try { BleFraming.fragment(ByteArray(ProtocolConstants.LAN_MAX_FRAME_BYTES + 1), 517, 0); fail() } catch (e: IllegalArgumentException) { /* ok */ }
    }

    @Test fun receiverIdHashIsStableAndShort() {
        val h = BleFraming.receiverIdHash("abc")
        assertEquals(ProtocolConstants.BLE_RID_HASH_BYTES, h.size)
        assertArrayEquals(h, BleFraming.receiverIdHash("abc"))
        assertTrue(!h.contentEquals(BleFraming.receiverIdHash("abd")))
    }
}
