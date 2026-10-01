package com.v2ray.ang.handler

import com.v2ray.ang.AppConfig
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * FILTERNET: tells apart "the country cut the international route" from
 * "this app is broken".
 *
 * That distinction is the whole point. During a shutdown the user sees a VPN
 * that will not connect and blames the app - this is what lets the app say,
 * truthfully, that the problem is upstream.
 */
object NetworkDiagnostics {

    /** Hosts that stay up on the domestic network during a shutdown. */
    private val DOMESTIC = listOf(
        "https://www.aparat.com",
        "https://www.digikala.com",
        "https://www.varzesh3.com",
    )

    /** Tiny endpoints abroad that answer with an empty body. */
    private val INTERNATIONAL = listOf(
        "https://www.gstatic.com/generate_204",
        "https://cp.cloudflare.com/generate_204",
        "https://connectivitycheck.gstatic.com/generate_204",
    )

    enum class Reach {
        /** Everything works. */
        FULL,

        /** Domestic sites load, the world does not: the national internet. */
        NATIONAL_ONLY,

        /** Nothing at all answers. */
        OFFLINE,

        /** Could not decide - treat as fine and stay quiet. */
        UNKNOWN,
    }

    suspend fun checkReachability(): Reach = withContext(Dispatchers.IO) {
        try {
            coroutineScope {
                val d = async { anyReachable(DOMESTIC, 4000) }
                val i = async { anyReachable(INTERNATIONAL, 5000) }
                val domestic = d.await()
                val international = i.await()
                when {
                    international -> Reach.FULL
                    domestic -> Reach.NATIONAL_ONLY
                    else -> Reach.OFFLINE
                }
            }
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Diagnostics: reachability failed", e)
            Reach.UNKNOWN
        }
    }

    private fun anyReachable(urls: List<String>, timeoutMs: Int): Boolean {
        for (u in urls) if (headOk(u, timeoutMs)) return true
        return false
    }

    private fun headOk(url: String, timeoutMs: Int): Boolean = try {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            instanceFollowRedirects = false
            setRequestProperty("Connection", "close")
        }
        val code = c.responseCode
        c.disconnect()
        code in 200..399
    } catch (e: Exception) {
        false
    }
}
