package com.torrent.app.net
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Watches the system default network; emits wifi=true with the current wifi IPv4
 *  when the default network is wifi, wifi=false (ip=null) otherwise. */
class WifiGateMonitor(private val context: Context) {

    data class State(val wifi: Boolean, val ip: String?)

    /** Dropout counters fed from the same samples that drive [state] (Stage 1 of the
     *  pps/wifi/thermal envelope plan): the only instrument that survives a crawler
     *  restart, because it lives in the app process, not in the daemon. */
    val gateStats = WifiGateStats()

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
                val wifi = WifiGateReducer.isWifi(transports)
                gateStats.onSample(wifi, SystemClock.elapsedRealtime())
                if (wifi) {
                    _state.value = State(true, wifiIpAddress())
                } else {
                    _state.value = State(false, null)
                }
            }

            override fun onLost(network: Network) {
                gateStats.onSample(false, SystemClock.elapsedRealtime())
                _state.value = State(false, null)
            }
        }
        cm!!.registerDefaultNetworkCallback(cb)
        callback = cb
    }

    fun stop() {
        callback?.let { cm?.unregisterNetworkCallback(it) }
        callback = null
        // A deliberate shutdown must not be recorded as a dropout.
        gateStats.noteDeliberateStop(SystemClock.elapsedRealtime())
        _state.value = State(false, null)
    }

    /** One instant's dropout counters, for the control dashboard's `api/status`. */
    fun gateStatsSnapshot(): WifiGateStats.Snapshot =
        gateStats.snapshot(SystemClock.elapsedRealtime())

    private fun wifiIpAddress(): String? {
        val wm = context.applicationContext
            .getSystemService(Context.WIFI_SERVICE) as WifiManager
        val ip = wm.connectionInfo.ipAddress
        if (ip == 0) return null
        return "${ip and 0xFF}.${(ip shr 8) and 0xFF}.${(ip shr 16) and 0xFF}.${(ip shr 24) and 0xFF}"
    }
}
