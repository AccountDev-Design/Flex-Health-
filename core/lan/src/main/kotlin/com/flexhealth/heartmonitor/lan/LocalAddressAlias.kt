package com.flexhealth.heartmonitor.lan

import okhttp3.Dns
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.UnknownHostException

/**
 * Nombres sintéticos para las IP de la red local, p. ej. `192.168.1.3` → `192-168-1-3.lan.flexheart.local`.
 *
 * Motivo: en Android (targetSdk ≥ 28) OkHttp consulta la Network Security Config antes de abrir una
 * conexión `ws://` y la configuración no admite rangos de IP ni comodines. La app del reloj permite tráfico
 * en claro **solo** para el dominio [DOMAIN] (res/xml/network_security_config.xml) y este módulo garantiza
 * que esos nombres únicamente resuelven a direcciones privadas, de enlace local o de loopback. Cualquier
 * otro destino sigue bloqueado. La confidencialidad e integridad las aporta el canal AES-256-GCM.
 */
object LocalAddressAlias {
    /** Debe coincidir con el `<domain>` de wear/src/main/res/xml/network_security_config.xml. */
    const val DOMAIN = "lan.flexheart.local"
    private const val V6_PREFIX = "v6-"
    private val IPV4 = Regex("^(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)(\\.(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)){3}$")
    private val IPV6_CHARS = Regex("^[0-9A-Fa-f:.]+$")

    /** Interpreta un literal IP **sin** resolver nombres por DNS. Devuelve `null` si no es un literal. */
    fun parseLiteral(host: String): InetAddress? {
        val h = host.trim().removePrefix("[").removeSuffix("]")
        return when {
            IPV4.matches(h) -> InetAddress.getByAddress(h.split('.').map { it.toInt().toByte() }.toByteArray())
            h.contains(':') && IPV6_CHARS.matches(h) -> runCatching { InetAddress.getByName(h) }.getOrNull() // literal: sin DNS
            else -> null
        }
    }

    /** IP de red local: privada (RFC 1918 / ULA fc00::/7), de enlace local o loopback. */
    fun isLocal(address: InetAddress): Boolean = when (address) {
        is Inet4Address -> address.isSiteLocalAddress || address.isLinkLocalAddress || address.isLoopbackAddress
        is Inet6Address -> address.isLinkLocalAddress || address.isLoopbackAddress || (address.address[0].toInt() and 0xfe) == 0xfc
        else -> false
    }

    /** Nombre sintético para un literal IP local; `null` si [host] no es una IP local. */
    fun aliasFor(host: String): String? {
        val addr = parseLiteral(host)?.takeIf { isLocal(it) } ?: return null
        val label = when (addr) {
            is Inet4Address -> addr.hostAddress.replace('.', '-')
            else -> V6_PREFIX + (addr.hostAddress ?: return null).substringBefore('%').replace(':', '-')
        }
        return "$label.$DOMAIN"
    }

    /** Inversa de [aliasFor]. Devuelve `null` si el nombre no es un alias válido de una IP local. */
    fun resolveAlias(hostname: String): InetAddress? {
        val name = hostname.lowercase().removeSuffix(".")
        if (!name.endsWith(".$DOMAIN")) return null
        val label = name.removeSuffix(".$DOMAIN")
        if (label.isEmpty() || label.contains('.')) return null
        val literal = if (label.startsWith(V6_PREFIX)) label.removePrefix(V6_PREFIX).replace('-', ':') else label.replace('-', '.')
        return parseLiteral(literal)?.takeIf { isLocal(it) }
    }

    /**
     * DNS del cliente LAN: solo resuelve alias de [DOMAIN] (sin consultar la red) y rechaza cualquier otro
     * nombre, de modo que el cliente WebSocket no puede conectarse fuera de la red local.
     */
    val dns: Dns = Dns { hostname ->
        val addr = resolveAlias(hostname) ?: throw UnknownHostException("Destino no permitido para el enlace local: $hostname")
        listOf(addr)
    }
}
