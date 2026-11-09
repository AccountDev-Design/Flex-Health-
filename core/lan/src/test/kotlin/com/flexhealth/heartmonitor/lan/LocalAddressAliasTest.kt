package com.flexhealth.heartmonitor.lan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.net.InetAddress
import java.net.UnknownHostException

class LocalAddressAliasTest {

    @Test fun tabletUrlUsesAllowedAliasDomain() {
        assertEquals("192-168-1-3.lan.flexheart.local", LocalAddressAlias.aliasFor("192.168.1.3"))
        assertEquals("ws://192-168-1-3.lan.flexheart.local:47820/flexheart", LanEndpoint("192.168.1.3", 47820).url)
    }

    @Test fun anyDynamicLocalAddressRoundTrips() {
        val local = listOf(
            "192.168.1.3", "192.168.0.254", "192.168.43.1", // router doméstico / hotspot
            "10.0.0.7", "10.255.255.254", "172.16.0.1", "172.31.200.9",
            "169.254.10.20", "127.0.0.1", "fe80::1", "fd12:3456::abcd", "::1",
        )
        for (ip in local) {
            val alias = LocalAddressAlias.aliasFor(ip) ?: error("sin alias para $ip")
            assertTrue(alias.endsWith(".${LocalAddressAlias.DOMAIN}"))
            assertEquals(ip, InetAddress.getByName(ip), LocalAddressAlias.resolveAlias(alias))
            assertEquals(listOf(InetAddress.getByName(ip)), LocalAddressAlias.dns.lookup(alias))
        }
    }

    @Test fun publicAddressesNeverGetAnAlias() {
        for (ip in listOf("8.8.8.8", "1.1.1.1", "172.32.0.1", "100.64.0.1", "2001:4860:4860::8888")) {
            assertNull(ip, LocalAddressAlias.aliasFor(ip))
        }
        assertEquals("ws://8.8.8.8:47820/flexheart", LanEndpoint("8.8.8.8", 47820).url)
    }

    @Test fun dnsRejectsAnythingThatIsNotALocalAlias() {
        val bad = listOf(
            "example.com", "tablet.local", "8-8-8-8.lan.flexheart.local", "192.168.1.3",
            "192-168-1-3.lan.flexheart.local.evil.com", "a.192-168-1-3.lan.flexheart.local",
            "999-1-1-1.lan.flexheart.local", "192-168-1.lan.flexheart.local", ".lan.flexheart.local", "lan.flexheart.local",
        )
        for (h in bad) {
            try { LocalAddressAlias.dns.lookup(h); fail("debería rechazar $h") } catch (e: UnknownHostException) { /* ok */ }
        }
    }

    @Test fun literalParsingNeverUsesDns() {
        assertNull(LocalAddressAlias.parseLiteral("tablet.local"))
        assertNull(LocalAddressAlias.parseLiteral("256.1.1.1"))
        assertNull(LocalAddressAlias.parseLiteral("01.2.3.4x"))
        assertEquals(InetAddress.getByName("192.168.1.3"), LocalAddressAlias.parseLiteral(" 192.168.1.3 "))
    }

    @Test fun aliasIsCaseAndTrailingDotInsensitive() {
        assertEquals(InetAddress.getByName("192.168.1.3"), LocalAddressAlias.resolveAlias("192-168-1-3.LAN.FlexHeart.Local."))
    }
}
