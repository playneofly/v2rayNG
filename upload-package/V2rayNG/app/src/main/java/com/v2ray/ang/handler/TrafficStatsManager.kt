package com.v2ray.ang.handler

import com.tencent.mmkv.MMKV
import com.v2ray.ang.AppConfig
import com.v2ray.ang.util.LogUtil
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * FILTERNET: central traffic accounting.
 *
 * Stores per-day uplink/downlink totals in a dedicated MMKV bucket so the
 * notification, the statistics screen and the live speed graph all read the
 * same numbers.
 */
object TrafficStatsManager {

    private const val ID_TRAFFIC = "TRAFFIC_STATS"
    private const val KEY_TOTAL_UP = "total_up"
    private const val KEY_TOTAL_DOWN = "total_down"
    private const val PREFIX_DAY_UP = "day_up_"
    private const val PREFIX_DAY_DOWN = "day_down_"
    private const val RETENTION_DAYS = 30

    private val storage by lazy { MMKV.mmkvWithID(ID_TRAFFIC, MMKV.MULTI_PROCESS_MODE) }
    private val dayFormat = SimpleDateFormat("yyyyMMdd", Locale.US)

    data class DayTraffic(val day: String, val up: Long, val down: Long) {
        val total: Long get() = up + down
    }

    private fun todayKey(): String = dayFormat.format(Date())

    private fun dayKey(daysAgo: Int): String {
        val cal = Calendar.getInstance()
        cal.add(Calendar.DAY_OF_YEAR, -daysAgo)
        return dayFormat.format(cal.time)
    }

    /**
     * Adds a freshly measured delta of bytes to today's counters and the all-time total.
     */
    fun record(uplinkBytes: Long, downlinkBytes: Long) {
        if (uplinkBytes <= 0L && downlinkBytes <= 0L) return
        try {
            val day = todayKey()
            storage.encode(PREFIX_DAY_UP + day, storage.decodeLong(PREFIX_DAY_UP + day, 0L) + uplinkBytes)
            storage.encode(PREFIX_DAY_DOWN + day, storage.decodeLong(PREFIX_DAY_DOWN + day, 0L) + downlinkBytes)
            storage.encode(KEY_TOTAL_UP, storage.decodeLong(KEY_TOTAL_UP, 0L) + uplinkBytes)
            storage.encode(KEY_TOTAL_DOWN, storage.decodeLong(KEY_TOTAL_DOWN, 0L) + downlinkBytes)
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to record traffic stats", e)
        }
    }

    fun today(): DayTraffic = dayAt(0)

    fun dayAt(daysAgo: Int): DayTraffic {
        val day = dayKey(daysAgo)
        return DayTraffic(
            day = day,
            up = storage.decodeLong(PREFIX_DAY_UP + day, 0L),
            down = storage.decodeLong(PREFIX_DAY_DOWN + day, 0L),
        )
    }

    /**
     * Returns the last [days] days ordered from oldest to newest (newest == today).
     */
    fun lastDays(days: Int = 7): List<DayTraffic> =
        (days - 1 downTo 0).map { dayAt(it) }

    fun totalUp(): Long = storage.decodeLong(KEY_TOTAL_UP, 0L)

    fun totalDown(): Long = storage.decodeLong(KEY_TOTAL_DOWN, 0L)

    fun reset() {
        try {
            storage.clearAll()
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to reset traffic stats", e)
        }
    }

    /**
     * Drops day buckets older than [RETENTION_DAYS] so the store cannot grow forever.
     */
    fun pruneOldDays() {
        try {
            val keep = (0 until RETENTION_DAYS).map { dayKey(it) }.toHashSet()
            storage.allKeys()?.forEach { key ->
                val day = when {
                    key.startsWith(PREFIX_DAY_UP) -> key.removePrefix(PREFIX_DAY_UP)
                    key.startsWith(PREFIX_DAY_DOWN) -> key.removePrefix(PREFIX_DAY_DOWN)
                    else -> null
                }
                if (day != null && day !in keep) storage.removeValueForKey(key)
            }
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to prune traffic stats", e)
        }
    }
}
