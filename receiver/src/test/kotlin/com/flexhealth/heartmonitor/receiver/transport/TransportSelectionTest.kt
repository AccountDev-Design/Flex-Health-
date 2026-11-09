package com.flexhealth.heartmonitor.receiver.transport

import com.flexhealth.heartmonitor.protocol.LinkState
import com.flexhealth.heartmonitor.receiver.data.MonitorMode
import com.flexhealth.heartmonitor.receiver.graph
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Selección manual del transporte en la tablet (Robolectric; no ejercita la radio Bluetooth real). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TransportSelectionTest {
    private val g = RuntimeEnvironment.getApplication().graph

    @Test fun eachModeMapsToItsOwnTransport() {
        assertSame(g.dataLayer, g.transportFor(MonitorMode.PHONE_BLUETOOTH))
        assertSame(g.lan, g.transportFor(MonitorMode.TABLET_WIFI))
        assertSame(g.ble, g.transportFor(MonitorMode.TABLET_BLE))
        assertTrue(MonitorMode.TABLET_WIFI.isHosted && MonitorMode.TABLET_BLE.isHosted)
        assertFalse(MonitorMode.PHONE_BLUETOOTH.isHosted)
        assertNull(g.hostedFor(MonitorMode.PHONE_BLUETOOTH))
    }

    @Test fun bleModeWithoutPermissionReportsWhyInsteadOfFailingSilently() {
        g.ble.start { }
        val s = g.ble.status.value
        assertEquals(LinkState.UNAVAILABLE, s.state)
        assertTrue(s.detail!!, s.detail!!.contains("permiso", ignoreCase = true) || s.detail!!.contains("Bluetooth"))
        assertFalse(g.ble.ready)
        g.ble.stop()
        assertEquals(LinkState.IDLE, g.ble.status.value.state)
    }
}
