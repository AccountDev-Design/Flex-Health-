package com.flexhealth.heartmonitor.wear.config

import com.flexhealth.heartmonitor.lan.LanEndpoint
import com.flexhealth.heartmonitor.lan.LocalAddressAlias
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * El reloj es el cliente WebSocket (OkHttp) y por tanto el sujeto a la Network Security Config.
 * Verifica que el tráfico en claro solo se permite para el dominio de alias locales y que ese dominio
 * coincide con el que genera el cliente LAN.
 */
class NetworkSecurityConfigTest {
    private val ns = "http://schemas.android.com/apk/res/android"
    private fun parse(f: File) = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder().parse(f).documentElement

    @Test fun cleartextOnlyForLocalAliasDomain() {
        val root = parse(File("src/main/res/xml/network_security_config.xml"))
        val base = root.getElementsByTagName("base-config").item(0) as Element
        assertEquals("false", base.getAttribute("cleartextTrafficPermitted"))
        val configs = root.getElementsByTagName("domain-config")
        assertEquals("un único dominio con excepción", 1, configs.length)
        val cfg = configs.item(0) as Element
        assertEquals("true", cfg.getAttribute("cleartextTrafficPermitted"))
        val domains = cfg.getElementsByTagName("domain")
        assertEquals(1, domains.length)
        val d = domains.item(0) as Element
        assertEquals(LocalAddressAlias.DOMAIN, d.textContent.trim())
        assertEquals("true", d.getAttribute("includeSubdomains"))
        // La URL que abre el cliente para la IP dinámica de la tablet cae dentro de ese dominio.
        val host = LanEndpoint("192.168.1.3", 47820).url.removePrefix("ws://").substringBefore(':')
        assertTrue(host.endsWith(".${LocalAddressAlias.DOMAIN}"))
    }

    @Test fun manifestUsesTheConfigAndDoesNotEnableGlobalCleartext() {
        val app = parse(File("src/main/AndroidManifest.xml")).getElementsByTagName("application").item(0) as Element
        assertEquals("@xml/network_security_config", app.getAttributeNS(ns, "networkSecurityConfig"))
        assertEquals("", app.getAttributeNS(ns, "usesCleartextTraffic"))
    }
}
