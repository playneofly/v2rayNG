package com.v2ray.ang.handler

import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.UrlContentRequest
import com.v2ray.ang.util.HttpUtil
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * FILTERNET: answers the two questions users actually ask.
 *
 *   "is the internet broken, or is it your app?"   -> [checkReachability]
 *   "why is it so slow?"                           -> [runDiagnostics]
 *
 * Every probe is a real request. Nothing here guesses.
 */
object NetworkDiagnostics {

    /* ───────────────────────── national internet ───────────────────────── */

    /** Hosts that stay up on the domestic network during a shutdown. */
    private val DOMESTIC = listOf(
        "https://www.aparat.com",
        "https://www.digikala.com",
        "https://www.varzesh3.com",
    )

    /** Tiny endpoints abroad that answer with no body. */
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

    /**
     * Distinguishes "Iran cut the international route" from "this app is broken",
     * which is the difference between the user blaming the shutdown and the user
     * uninstalling FILTERNET.
     */
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

    /* ───────────────────────── "why is it slow?" ───────────────────────── */

    enum class Status { OK, SLOW, FAIL, SKIPPED }

    data class Check(
        val id: String,
        val status: Status,
        /** Milliseconds, or bytes-per-second for the throughput check. */
        val value: Long = 0,
    )

    data class Report(
        val checks: List<Check>,
        val verdict: Verdict,
    )

    enum class Verdict {
        ALL_GOOD,
        NOT_CONNECTED,
        NATIONAL_INTERNET,
        TUNNEL_DEAD,
        SERVER_SLOW,
        PARTIALLY_BLOCKED,
    }

    const val CHECK_TUNNEL = "tunnel"
    const val CHECK_SPEED = "speed"
    const val CHECK_INSTAGRAM = "instagram"
    const val CHECK_YOUTUBE = "youtube"
    const val CHECK_TELEGRAM = "telegram"
    const val CHECK_WHATSAPP = "whatsapp"
    const val CHECK_OPENAI = "openai"
    const val CHECK_GOOGLE = "google"

    private val SERVICE_URLS = mapOf(
        CHECK_INSTAGRAM to "https://i.instagram.com/favicon.ico",
        CHECK_YOUTUBE to "https://www.youtube.com/favicon.ico",
        CHECK_TELEGRAM to "https://web.telegram.org/favicon.ico",
        CHECK_WHATSAPP to "https://web.whatsapp.com/favicon.ico",
        CHECK_OPENAI to "https://chat.openai.com/favicon.ico",
        CHECK_GOOGLE to "https://www.google.com/generate_204",
    )

    /** Throughput below this is reported as slow. 150 KB/s ~ 1.2 Mbit. */
    private const val SLOW_BPS = 150_000L

    /**
     * Runs the whole chain and turns it into a verdict a non-technical user can
     * act on. Must be called while the tunnel is up, otherwise everything below
     * simply measures the plain connection.
     */
    suspend fun runDiagnostics(isRunning: Boolean): Report = withContext(Dispatchers.IO) {
        if (!isRunning) {
            val reach = checkReachability()
            return@withContext Report(
                checks = listOf(Check(CHECK_TUNNEL, Status.SKIPPED)),
                verdict = if (reach == Reach.NATIONAL_ONLY) Verdict.NATIONAL_INTERNET
                else Verdict.NOT_CONNECTED,
            )
        }

        val checks = mutableListOf<Check>()

        // 1. does anything at all pass through the tunnel?
        val t0 = System.currentTimeMillis()
        val tunnelOk = anyReachable(INTERNATIONAL, 6000)
        val tunnelMs = System.currentTimeMillis() - t0
        checks += Check(CHECK_TUNNEL, if (tunnelOk) Status.OK else Status.FAIL, tunnelMs)

        if (!tunnelOk) {
            return@withContext Report(checks, Verdict.TUNNEL_DEAD)
        }

        // 2. how fast is it really? a small real download, not a ping
        val bps = measureThroughput()
        checks += Check(
            CHECK_SPEED,
            when {
                bps <= 0 -> Status.FAIL
                bps < SLOW_BPS -> Status.SLOW
                else -> Status.OK
            },
            bps,
        )

        // 3. the services people actually care about, in parallel
        coroutineScope {
            SERVICE_URLS.map { (id, url) ->
                async { Check(id, if (headOk(url, 7000)) Status.OK else Status.FAIL) }
            }.forEach { checks += it.await() }
        }

        val serviceChecks = checks.filter { it.id in SERVICE_URLS.keys }
        val failed = serviceChecks.count { it.status == Status.FAIL }
        val verdict = when {
            bps in 1 until SLOW_BPS -> Verdict.SERVER_SLOW
            failed >= serviceChecks.size -> Verdict.TUNNEL_DEAD
            failed > 0 -> Verdict.PARTIALLY_BLOCKED
            else -> Verdict.ALL_GOOD
        }
        Report(checks, verdict)
    }

    /**
     * Downloads a small fixed asset and measures how fast it arrived.
     *
     * @return bytes per second, or -1 when the download failed.
     */
    private fun measureThroughput(): Long = try {
        val url = "https://speed.cloudflare.com/__down?bytes=1000000"
        val t0 = System.currentTimeMillis()
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 6000
            readTimeout = 12000
            setRequestProperty("Connection", "close")
        }
        var total = 0L
        c.inputStream.use { input ->
            val buf = ByteArray(16 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                total += n
                if (System.currentTimeMillis() - t0 > 10_000) break
            }
        }
        c.disconnect()
        val secs = (System.currentTimeMillis() - t0).coerceAtLeast(1) / 1000.0
        if (total <= 0) -1L else (total / secs).toLong()
    } catch (e: Exception) {
        LogUtil.e(AppConfig.TAG, "Diagnostics: throughput failed", e)
        -1L
    }

    /** Quick check used by the live service checklist on the home screen. */
    suspend fun quickServiceCheck(): Map<String, Boolean> = withContext(Dispatchers.IO) {
        coroutineScope {
            SERVICE_URLS.map { (id, url) ->
                async { id to headOk(url, 6000) }
            }.associate { it.await() }
        }
    }
}
