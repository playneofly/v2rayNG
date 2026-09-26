package com.v2ray.ang.ui.main

import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.UrlContentRequest
import com.v2ray.ang.util.HttpUtil
import com.v2ray.ang.util.LogUtil

/**
 * FILTERNET: asks a couple of tiny "what is my IP" endpoints for the public
 * address currently in use. It is what finally lets the user *see* that the
 * tunnel is carrying their traffic instead of just trusting a green dot.
 *
 * Runs only while the VPN is up, so the request already travels through the
 * tunnel and needs no proxy configuration of its own.
 */
internal object PublicIpProbe {

    private val ENDPOINTS = listOf(
        "https://api.ipify.org",
        "https://ipv4.icanhazip.com",
        "https://checkip.amazonaws.com",
    )

    private val IPV4 = Regex("""\b(?:\d{1,3}\.){3}\d{1,3}\b""")

    fun lookup(): String? {
        for (url in ENDPOINTS) {
            val body = runCatching {
                HttpUtil.getUrlContent(UrlContentRequest(url = url, timeout = 6000))
            }.getOrNull()
            val ip = body?.let { IPV4.find(it.trim())?.value }
            if (!ip.isNullOrBlank()) {
                LogUtil.i(AppConfig.TAG, "PublicIpProbe: $ip")
                return ip
            }
        }
        return null
    }
}
