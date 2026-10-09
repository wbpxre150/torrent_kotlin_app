package com.torrent.app.ftp
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Watches the system default network; emits wifi=true with the current wifi IPv4
 *  when the default network is wifi, wifi=false (ip=null) otherwise. */
class WifiGateMonitor(private val context: Context) {

    data class State(val wifi: Boolean, val ip: String?)

    private val _state = MutableStateFlow(State(false, null))
    val state: StateFlow<State> = _state.asStateFlow()

    private var cm: ConnectivityManager? = null
    private var callback: ConnectivityManager.NetworkCallback? = null

    fun start() {
        if (callback != null) return
        cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                val transports = mutableSetOf<Int>()
                for (t in intArrayOf(
                        NetworkCapabilities.TRANSPORT_WIFI,
                        NetworkCapabilities.TRANSPORT_CELLULAR,
                        NetworkCapabilities.TRANSPORT_ETHERNET,
                        NetworkCapabilities.TRANSPORT_VPN)) {
                    if (caps.hasTransport(t)) transports.add(t)
                }
                if (WifiGateReducer.isWifi(transports)) {
                    _state.value = State(true, wifiIpAddress())
                } else {
                    _state.value = State(false, null)
                }
            }
            override fun onLost(network: Network) { _state.value = State(false, null) }
        }
        cm!!.registerDefaultNetworkCallback(cb)
        callback = cb
    }

    fun stop() {
        callback?.let { cm?.unregisterNetworkCallback(it) }
        callback = null
        _state.value = State(false, null)
    }

    private fun wifiIpAddress(): String? {
        val wm = context.applicationContext
            .getSystemService(Context.WIFI_SERVICE) as WifiManager
        val ip = wm.connectionInfo.ipAddress
        if (ip == 0) return null
        return "${ip and 0xFF}.${(ip shr 8) and 0xFF}.${(ip shr 16) and 0xFF}.${(ip shr 24) and 0xFF}"
    }
}