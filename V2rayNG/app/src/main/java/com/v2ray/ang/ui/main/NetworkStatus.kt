package com.v2ray.ang.ui.main

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * FILTERNET: is there any usable internet at all right now?
 *
 * Needed so the connect button can turn red and say "no internet" instead of
 * spinning forever on a dead network. While the VPN itself is up the tunnel
 * counts as a validated transport, so this stays true.
 */
@Composable
internal fun rememberHasInternet(): State<Boolean> {
    val context = LocalContext.current
    val state = remember { mutableStateOf(currentlyOnline(context)) }

    DisposableEffect(context) {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        if (cm == null) {
            state.value = true // cannot tell - do not block the user
            onDispose { }
        } else {
            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    state.value = currentlyOnline(context)
                }

                override fun onLost(network: Network) {
                    state.value = currentlyOnline(context)
                }

                override fun onCapabilitiesChanged(
                    network: Network,
                    capabilities: NetworkCapabilities,
                ) {
                    state.value = currentlyOnline(context)
                }
            }
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            runCatching { cm.registerNetworkCallback(request, callback) }
            state.value = currentlyOnline(context)
            onDispose { runCatching { cm.unregisterNetworkCallback(callback) } }
        }
    }
    return state
}

private fun currentlyOnline(context: Context): Boolean = runCatching {
    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        ?: return@runCatching true
    val caps = cm.getNetworkCapabilities(cm.activeNetwork ?: return@runCatching false)
        ?: return@runCatching false
    caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
        (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN))
}.getOrDefault(true)
