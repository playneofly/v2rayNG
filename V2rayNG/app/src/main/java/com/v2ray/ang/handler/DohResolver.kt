package com.v2ray.ang.handler

import com.v2ray.ang.AppConfig
import com.v2ray.ang.util.LogUtil
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import java.net.URLEncoder

/**
 * FILTERNET: name resolution that cannot be lied to.
 *
 * Plain DNS inside Iran is routinely answered by the network rather than by
 * the real nameserver, so asking the system resolver for a clean-IP hostname
 * can hand back whatever the filter feels like. Every answer we actually rely
 * on therefore comes over HTTPS, where the response is end-to-end protected.
 *
 * The system resolver is still kept as a last resort: a possibly-poisoned
 * answer beats no answer at all, and the addresses get verified by a real
 * handshake afterwards anyway.
 */
object DohResolver {

    /**
     * Resolvers are tried in order. The first is itself reachable through the
     * Cloudflare edge, which tends to survive when other things do not.
     */
    private val ENDPOINTS = listOf(
        "https://cloudflare-dns.com/dns-query?type=A&name=",
        "https://dns.google/resolve?type=A&name=",
        "https://dns.quad9.net:5053/dns-query?type=A&name=",
    )

    private const val TIMEOUT_MS = 6000

    /**
     * Every A record for [host], newest answer first.
     *
     * @return addresses, or an empty list when nothing could be resolved.
     */
    fun resolve(host: String): List<String> {
        if (host.isBlank()) return emptyList()
        for (endpoint in ENDPOINTS) {
            val answers = query(endpoint + URLEncoder.encode(host, "UTF-8"))
            if (answers.isNotEmpty()) {
                LogUtil.i(AppConfig.TAG, "DohResolver: $host -> ${answers.size} via ${endpoint.take(28)}")
                return answers
            }
        }
        // Last resort: the system resolver. Possibly poisoned, still better than
        // nothing because every address is proven by handshake later anyway.
        return runCatching {
            InetAddress.getAllByName(host)
                .mapNotNull { it.hostAddress }
                .filter { it.count { c -> c == '.' } == 3 }
        }.getOrDefault(emptyList())
    }

    private fun query(url: String): List<String> = runCatching {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            // Both Cloudflare and Google answer JSON when asked for it.
            setRequestProperty("accept", "application/dns-json")
            setRequestProperty("Connection", "close")
        }
        if (c.responseCode !in 200..299) {
            c.disconnect()
            return@runCatching emptyList()
        }
        val body = c.inputStream.bufferedReader().use { it.readText() }
        c.disconnect()
        parseAnswers(body)
    }.getOrDefault(emptyList())

    /**
     * Pulls the A records out of a DoH JSON answer.
     *
     * Kept separate so it can be unit tested without a network.
     */
    fun parseAnswers(json: String): List<String> = runCatching {
        val arr = JSONObject(json).optJSONArray("Answer") ?: return@runCatching emptyList()
        val out = ArrayList<String>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            // type 1 is an A record; CNAMEs in the chain are type 5 and ignored.
            if (o.optInt("type") != 1) continue
            val data = o.optString("data").trim()
            if (isIpv4(data)) out.add(data)
        }
        out.distinct()
    }.getOrDefault(emptyList())

    fun isIpv4(s: String): Boolean {
        val parts = s.split(".")
        if (parts.size != 4) return false
        return parts.all { p ->
            p.isNotEmpty() && p.length <= 3 && p.all { it.isDigit() } &&
                (p.toIntOrNull() ?: -1) in 0..255
        }
    }
}
