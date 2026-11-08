package com.flexhealth.heartmonitor.protocol

/** Estado de un enlace de transporte (Data Layer o red local), común a todas las apps. */
enum class LinkState {
    IDLE,
    SEARCHING,
    CONNECTING,
    PAIRING,
    AUTHENTICATING,
    CONNECTED,
    /** El transporte no puede funcionar (p. ej. sin Wi-Fi o sin nodos). */
    UNAVAILABLE,
    ERROR,
}

data class LinkStatus(
    val state: LinkState = LinkState.IDLE,
    val peerName: String? = null,
    val detail: String? = null,
    /** Data Layer: `true` si el nodo está conectado directamente (Node.isNearby). */
    val nearby: Boolean? = null,
    val rttMs: Long? = null,
)
