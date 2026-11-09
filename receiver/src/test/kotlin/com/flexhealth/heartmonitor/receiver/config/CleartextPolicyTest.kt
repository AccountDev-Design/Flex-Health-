package com.flexhealth.heartmonitor.receiver.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * El receptor (mobile y tablet) solo actúa como servidor WebSocket: no abre conexiones en claro, así que
 * no necesita excepciones de Network Security Config y conserva el bloqueo por defecto (targetSdk ≥ 28).
 */
class CleartextPolicyTest {
    private val ns = "http://schemas.android.com/apk/res/android"

    @Test fun receiverDoesNotEnableCleartext() {
        for (path in listOf("src/main/AndroidManifest.xml", "src/mobile/AndroidManifest.xml", "src/tablet/AndroidManifest.xml")) {
            val f = File(path)
            if (!f.exists()) continue
            val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder().parse(f)
            val apps = doc.getElementsByTagName("application")
            for (i in 0 until apps.length) {
                val app = apps.item(i) as Element
                assertEquals(path, "", app.getAttributeNS(ns, "usesCleartextTraffic"))
                assertEquals(path, "", app.getAttributeNS(ns, "networkSecurityConfig"))
            }
        }
        assertFalse(File("src/main/res/xml/network_security_config.xml").exists())
    }
}
