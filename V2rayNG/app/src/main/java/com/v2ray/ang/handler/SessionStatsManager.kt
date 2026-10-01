package com.v2ray.ang.handler

import com.tencent.mmkv.MMKV
import com.v2ray.ang.AppConfig
import com.v2ray.ang.util.LogUtil
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * FILTERNET: everything the app knows about how the user actually uses it.
 *
 * Feeds three features that all need the same raw material:
 *   · the end-of-session receipt
 *   · the monthly recap ("your month with FILTERNET")
 *   · the "this is slower than usual" warning
 *
 * Nothing here ever leaves the device.
 */
object SessionStatsManager {

    private const val ID = "FILTERNET_SESSIONS"

    private const val KEY_START_AT = "cur_start_at"
    private const val KEY_START_DOWN = "cur_start_down"
    private const val KEY_START_UP = "cur_start_up"
    private const val KEY_COUNTRY = "cur_country"

    private const val KEY_LAST = "last_session"

    private const val PREFIX_MONTH_SECONDS = "m_sec_"
    private const val PREFIX_MONTH_BYTES = "m_byt_"
    private const val PREFIX_MONTH_COUNT = "m_cnt_"
    private const val PREFIX_HOUR = "m_hour_"
    private const val PREFIX_COUNTRY = "m_cty_"

    /** Rolling average of throughput, used to spot an unusually bad session. */
    private const val KEY_AVG_BPS = "avg_bps"
    private const val KEY_AVG_SAMPLES = "avg_samples"

    private val storage by lazy { MMKV.mmkvWithID(ID, MMKV.MULTI_PROCESS_MODE) }

    private val monthFmt = SimpleDateFormat("yyyyMM", Locale.US)

    /* ─────────────────────────── session lifecycle ─────────────────────────── */

    data class Session(
        val startedAt: Long,
        val durationSeconds: Long,
        val down: Long,
        val up: Long,
        val country: String?,
    ) {
        val total: Long get() = down + up
        /** Average throughput in bytes per second over the whole session. */
        val averageBps: Long
            get() = if (durationSeconds <= 0) 0 else total / durationSeconds
    }

    /** Called the moment the tunnel really comes up. */
    fun onConnected() = runCatching {
        if (storage.decodeLong(KEY_START_AT, 0L) > 0L) return@runCatching
        storage.encode(KEY_START_AT, System.currentTimeMillis())
        storage.encode(KEY_START_DOWN, TrafficStatsManager.totalDown())
        storage.encode(KEY_START_UP, TrafficStatsManager.totalUp())
        storage.encode(KEY_COUNTRY, "")
    }.onFailure { LogUtil.e(AppConfig.TAG, "SessionStats: onConnected failed", it) }

    /** The country of the exit node, learned a little after the handshake. */
    fun setCountry(country: String?) = runCatching {
        if (!country.isNullOrBlank()) storage.encode(KEY_COUNTRY, country)
    }.getOrNull()

    /**
     * Closes the current session and folds it into the monthly totals.
     *
     * @return the finished session, or null when there was nothing open.
     */
    fun onDisconnected(): Session? = runCatching {
        val startedAt = storage.decodeLong(KEY_START_AT, 0L)
        if (startedAt <= 0L) return@runCatching null

        val now = System.currentTimeMillis()
        val seconds = ((now - startedAt) / 1000).coerceAtLeast(0)
        val down = (TrafficStatsManager.totalDown() - storage.decodeLong(KEY_START_DOWN, 0L))
            .coerceAtLeast(0)
        val up = (TrafficStatsManager.totalUp() - storage.decodeLong(KEY_START_UP, 0L))
            .coerceAtLeast(0)
        val country = storage.decodeString(KEY_COUNTRY, "").orEmpty().ifBlank { null }

        storage.remove(KEY_START_AT)

        // Sessions shorter than half a minute are noise - usually a failed try.
        if (seconds < 30) return@runCatching null

        val session = Session(startedAt, seconds, down, up, country)
        fold(session)
        storage.encode(KEY_LAST, "$startedAt|$seconds|$down|$up|${country.orEmpty()}")
        session
    }.onFailure { LogUtil.e(AppConfig.TAG, "SessionStats: onDisconnected failed", it) }.getOrNull()

    fun lastSession(): Session? = runCatching {
        val raw = storage.decodeString(KEY_LAST).orEmpty()
        if (raw.isBlank()) return@runCatching null
        val p = raw.split("|")
        if (p.size < 5) return@runCatching null
        Session(
            startedAt = p[0].toLongOrNull() ?: return@runCatching null,
            durationSeconds = p[1].toLongOrNull() ?: 0L,
            down = p[2].toLongOrNull() ?: 0L,
            up = p[3].toLongOrNull() ?: 0L,
            country = p[4].ifBlank { null },
        )
    }.getOrNull()

    fun isSessionOpen(): Boolean =
        runCatching { storage.decodeLong(KEY_START_AT, 0L) > 0L }.getOrDefault(false)

    /** Seconds elapsed in the session that is currently open. */
    fun currentSessionSeconds(): Long = runCatching {
        val s = storage.decodeLong(KEY_START_AT, 0L)
        if (s <= 0L) 0L else ((System.currentTimeMillis() - s) / 1000).coerceAtLeast(0)
    }.getOrDefault(0L)

    /* ─────────────────────────── monthly recap ─────────────────────────── */

    data class MonthRecap(
        val monthKey: String,
        val totalSeconds: Long,
        val totalBytes: Long,
        val sessions: Int,
        /** 0..23, the hour the user connects most often. */
        val favouriteHour: Int?,
        val favouriteCountry: String?,
    ) {
        val hasData: Boolean get() = sessions > 0 && totalSeconds > 0
    }

    private fun fold(s: Session) {
        val key = monthFmt.format(Date(s.startedAt))
        storage.encode(PREFIX_MONTH_SECONDS + key, monthSeconds(key) + s.durationSeconds)
        storage.encode(PREFIX_MONTH_BYTES + key, monthBytes(key) + s.total)
        storage.encode(PREFIX_MONTH_COUNT + key, monthCount(key) + 1)

        val cal = Calendar.getInstance().apply { timeInMillis = s.startedAt }
        val hour = cal.get(Calendar.HOUR_OF_DAY)
        val hk = "$PREFIX_HOUR$key$hour"
        storage.encode(hk, storage.decodeInt(hk, 0) + 1)

        s.country?.let {
            val ck = "$PREFIX_COUNTRY$key$it"
            storage.encode(ck, storage.decodeInt(ck, 0) + 1)
        }

        updateAverage(s)
    }

    private fun monthSeconds(k: String) = storage.decodeLong(PREFIX_MONTH_SECONDS + k, 0L)
    private fun monthBytes(k: String) = storage.decodeLong(PREFIX_MONTH_BYTES + k, 0L)
    private fun monthCount(k: String) = storage.decodeInt(PREFIX_MONTH_COUNT + k, 0)

    fun recap(monthsAgo: Int = 0): MonthRecap = runCatching {
        val cal = Calendar.getInstance().apply { add(Calendar.MONTH, -monthsAgo) }
        val key = monthFmt.format(cal.time)

        val hour = (0..23)
            .map { it to storage.decodeInt("$PREFIX_HOUR$key$it", 0) }
            .filter { it.second > 0 }
            .maxByOrNull { it.second }?.first

        val country = KNOWN_COUNTRIES
            .map { it to storage.decodeInt("$PREFIX_COUNTRY$key$it", 0) }
            .filter { it.second > 0 }
            .maxByOrNull { it.second }?.first

        MonthRecap(key, monthSeconds(key), monthBytes(key), monthCount(key), hour, country)
    }.getOrDefault(MonthRecap("", 0, 0, 0, null, null))

    /**
     * Countries we bother to tally. Keeping a fixed list avoids having to walk
     * every key in storage, which MMKV makes awkward.
     */
    private val KNOWN_COUNTRIES = listOf(
        "Germany", "Netherlands", "France", "United Kingdom", "Finland", "Sweden",
        "Poland", "Turkey", "United Arab Emirates", "United States", "Canada",
        "Singapore", "Japan", "Austria", "Switzerland", "Romania", "Lithuania",
    )

    /* ─────────────────────────── anomaly detection ─────────────────────────── */

    private fun updateAverage(s: Session) {
        // Only sessions that actually moved data say anything about speed.
        if (s.durationSeconds < 60 || s.total < 1_000_000) return
        val n = storage.decodeInt(KEY_AVG_SAMPLES, 0)
        val avg = storage.decodeLong(KEY_AVG_BPS, 0L)
        val newAvg = if (n == 0) s.averageBps else (avg * n + s.averageBps) / (n + 1)
        storage.encode(KEY_AVG_BPS, newAvg)
        storage.encode(KEY_AVG_SAMPLES, (n + 1).coerceAtMost(50))
    }

    fun averageBps(): Long = runCatching { storage.decodeLong(KEY_AVG_BPS, 0L) }.getOrDefault(0L)

    fun hasSpeedBaseline(): Boolean =
        runCatching { storage.decodeInt(KEY_AVG_SAMPLES, 0) >= 3 }.getOrDefault(false)

    /**
     * True when the live throughput is far below what this user normally gets.
     *
     * Deliberately conservative: it needs a baseline of at least three real
     * sessions and a drop past [ANOMALY_FACTOR], so a quiet moment with no
     * traffic never triggers it.
     */
    fun isAnomalouslySlow(currentBps: Long): Boolean {
        if (!hasSpeedBaseline()) return false
        val avg = averageBps()
        if (avg <= 0L) return false
        if (currentBps <= 0L) return false
        return currentBps * ANOMALY_FACTOR < avg
    }

    private const val ANOMALY_FACTOR = 3

    /* ─────────────────────── carrier benchmark ─────────────────────── */

    private const val PREFIX_CARRIER_BPS = "car_bps_"
    private const val PREFIX_CARRIER_N = "car_n_"

    /**
     * FILTERNET: average throughput for one carrier / network, measured by this
     * phone only. There is no backend, so the honest comparison is the user
     * against their own history on each network rather than against strangers.
     */
    fun recordCarrierSample(carrier: String, bps: Long) = runCatching {
        if (carrier.isBlank() || bps <= 0) return@runCatching
        val k = carrier.lowercase().replace(' ', '_')
        val n = storage.decodeInt(PREFIX_CARRIER_N + k, 0)
        val avg = storage.decodeLong(PREFIX_CARRIER_BPS + k, 0L)
        val next = if (n == 0) bps else (avg * n + bps) / (n + 1)
        storage.encode(PREFIX_CARRIER_BPS + k, next)
        storage.encode(PREFIX_CARRIER_N + k, (n + 1).coerceAtMost(100))
    }.getOrNull()

    data class CarrierStat(val carrier: String, val averageBps: Long, val samples: Int)

    fun carrierStat(carrier: String): CarrierStat? = runCatching {
        val k = carrier.lowercase().replace(' ', '_')
        val n = storage.decodeInt(PREFIX_CARRIER_N + k, 0)
        if (n <= 0) return@runCatching null
        CarrierStat(carrier, storage.decodeLong(PREFIX_CARRIER_BPS + k, 0L), n)
    }.getOrNull()

    /** Every carrier this phone has gathered numbers for, best first. */
    fun allCarrierStats(carriers: List<String>): List<CarrierStat> =
        carriers.mapNotNull { carrierStat(it) }.sortedByDescending { it.averageBps }

    fun clear() = runCatching { storage.clearAll() }.getOrNull()
}
