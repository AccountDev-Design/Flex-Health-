package com.flexhealth.heartmonitor.core.android

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import com.flexhealth.heartmonitor.protocol.ProtocolConstants

/** Anuncia el servidor local de la tablet mediante mDNS / DNS-SD. */
class NsdAdvertiser(context: Context) {
    private val nsd = context.getSystemService(NsdManager::class.java)
    private var listener: NsdManager.RegistrationListener? = null

    var registeredName: String? = null
        private set

    fun register(serviceName: String, port: Int, receiverId: String, onResult: (Boolean, String?) -> Unit = { _, _ -> }) {
        unregister()
        val info = NsdServiceInfo().apply {
            this.serviceName = serviceName
            serviceType = ProtocolConstants.NSD_SERVICE_TYPE
            this.port = port
            setAttribute(ProtocolConstants.NSD_TXT_RECEIVER_ID, receiverId)
            setAttribute(ProtocolConstants.NSD_TXT_PROTOCOL, ProtocolConstants.PROTOCOL_VERSION.toString())
        }
        val l = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(s: NsdServiceInfo) { registeredName = s.serviceName; onResult(true, null) }
            override fun onRegistrationFailed(s: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "mDNS registration failed: $errorCode"); onResult(false, "mDNS error $errorCode")
            }
            override fun onServiceUnregistered(s: NsdServiceInfo) { registeredName = null }
            override fun onUnregistrationFailed(s: NsdServiceInfo, errorCode: Int) = Unit
        }
        listener = l
        nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, l)
    }

    fun unregister() {
        listener?.let { runCatching { nsd.unregisterService(it) } }
        listener = null
        registeredName = null
    }

    private companion object { const val TAG = "NsdAdvertiser" }
}
