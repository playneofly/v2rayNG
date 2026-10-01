package com.v2ray.ang.handler

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.telephony.TelephonyManager
import com.v2ray.ang.AppConfig
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

/**
 * FILTERNET: clean addresses curated per Iranian operator.
 *
 * ircf.space publishes, for each operator, a hostname whose A records are
 * edge addresses that currently work on that operator. The list is maintained
 * by people testing from inside the country, which is information no amount of
 * scanning from the phone can match for speed.
 *
 * Two flavours exist per operator:
 *   mtn.ircf.space    one address, the current pick
 *   mtnc.ircf.space   several addresses at once  <- what we want
 *
 * Distribution is over DNS rather than a web page, so the list keeps working
 * even when the site itself is unreachable - as long as we resolve it somewhere
 * honest, which is why [DohResolver] exists.
 */
object IrcfSource {

    private const val DOMAIN = "ircf.space"

    /** Mobile operators, keyed by the MCC-MNC the SIM reports. */
    private val BY_MCC_MNC = mapOf(
        "43235" to "mtn",   // Irancell
        "43211" to "mci",   // Hamrah-e Avval
        "43220" to "rtl",   // Rightel
        "43232" to "mci",   // Taliya - no list of its own, MCI is the closest
        "43219" to "mci",
        "43270" to "mkh",   // MTCE / Telecom
        "43293" to "mci",
    )

    /** Fixed-line providers, tried together when the phone is on Wi-Fi. */
    private val FIXED_LINE = listOf("sht", "ast", "hwb", "prs", "mbt", "shm", "rsp", "sbn")

    /** Always worth asking, whatever the network. */
    private const val GENERIC = "cname"

    private const val CACHE_KEY = "fn_ircf_cache"
    private const val CACHE_STAMP = "fn_ircf_cache_at"
    private const val CACHE_TTL_MS = 3L * 60 * 60 * 1000

    data class Result(val carrier: String, val codes: List<String>, val addresses: List<String>)

    /** Human readable name of the network the phone is on right now. */
    fun carrierName(context: Context): String = runCatching {
        if (isWifi(context)) return@runCatching "Wi-Fi"
        val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        tm?.networkOperatorName?.takeIf { it.isNotBlank() } ?: "Mobile"
    }.getOrDefault("Mobile")

    private fun isWifi(context: Context): Boolean = runCatching {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return@runCatching false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork ?: return@runCatching false)
            ?: return@runCatching false
        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
            !caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
    }.getOrDefault(false)

    /**
     * Which ircf prefixes to ask for on this network.
     *
     * On mobile the SIM tells us exactly who we are talking to. On Wi-Fi there
     * is no reliable way to know the ISP, so the fixed-line providers are all
     * asked at once and the answers pooled - a few extra lookups cost nothing
     * next to the alternative of guessing wrong.
     */
    fun codesFor(context: Context): List<String> {
        if (isWifi(context)) return listOf(GENERIC) + FIXED_LINE
        val mccMnc = runCatching {
            val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
            tm?.simOperator.orEmpty().ifBlank { tm?.networkOperator.orEmpty() }
        }.getOrDefault("")
        val code = BY_MCC_MNC[mccMnc]
        return if (code != null) listOf(code, GENERIC) else listOf(GENERIC) + FIXED_LINE
    }

    /** Maps one prefix to the hostnames worth resolving, best first. */
    fun hostsFor(code: String): List<String> = listOf("${code}c.$DOMAIN", "$code.$DOMAIN")

    /**
     * Fetches clean addresses for this network.
     *
     * Results are cached so a later attempt during a shutdown - when no name
     * can be resolved at all - still has something to work with.
     */
    suspend fun fetch(context: Context): Result = withContext(Dispatchers.IO) {
        val codes = codesFor(context)
        val carrier = carrierName(context)

        val fresh = runCatching {
            coroutineScope {
                codes.flatMap { hostsFor(it) }
                    .map { host -> async { DohResolver.resolve(host) } }
                    .flatMap { it.await() }
            }
        }.getOrDefault(emptyList())
            .filter { DohResolver.isIpv4(it) }
            .distinct()

        if (fresh.isNotEmpty()) {
            runCatching {
                MmkvManager.encodeSettings(CACHE_KEY, fresh.joinToString(","))
                MmkvManager.encodeSettings(CACHE_STAMP, System.currentTimeMillis().toString())
            }
            LogUtil.i(AppConfig.TAG, "IrcfSource: ${fresh.size} addresses for $carrier ($codes)")
            return@withContext Result(carrier, codes, fresh)
        }

        // Nothing resolved - fall back to whatever we saw last time.
        val cached = runCatching {
            MmkvManager.decodeSettingsString(CACHE_KEY).orEmpty()
                .split(",").map { it.trim() }.filter { DohResolver.isIpv4(it) }
        }.getOrDefault(emptyList())
        LogUtil.w(AppConfig.TAG, "IrcfSource: live lookup failed, ${cached.size} cached")
        Result(carrier, codes, cached)
    }

    fun cacheAgeMs(): Long = runCatching {
        val at = MmkvManager.decodeSettingsString(CACHE_STAMP)?.toLongOrNull() ?: return@runCatching -1L
        System.currentTimeMillis() - at
    }.getOrDefault(-1L)

    fun isCacheFresh(): Boolean = cacheAgeMs() in 0 until CACHE_TTL_MS
}
