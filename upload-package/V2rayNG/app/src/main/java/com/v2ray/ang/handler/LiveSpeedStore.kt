package com.v2ray.ang.handler

import com.tencent.mmkv.MMKV
import com.v2ray.ang.AppConfig
import com.v2ray.ang.util.LogUtil

/**
 * FILTERNET: cross-process holder for the most recent speed samples.
 *
 * The core runs in the ":daemon" process while the UI lives in the main
 * process, so an in-memory flow cannot be shared. A tiny multi-process MMKV
 * bucket is used instead: the service writes, the UI polls.
 */
object LiveSpeedStore {

    private const val ID_SPEED = "LIVE_SPEED"
    private const val KEY_UP = "up"
    private const val KEY_DOWN = "down"
    private const val KEY_HISTORY = "history"
    private const val KEY_UPDATED_AT = "updated_at"

    const val HISTORY_SIZE = 60

    private val storage by lazy { MMKV.mmkvWithID(ID_SPEED, MMKV.MULTI_PROCESS_MODE) }

    data class Sample(val up: Long, val down: Long, val history: List<Long>, val updatedAt: Long)

    /**
     * Publishes one measurement (bytes per second) from the service process.
     */
    fun publish(upPerSec: Long, downPerSec: Long) {
        try {
            val history = readHistory().toMutableList()
            history.add(downPerSec.coerceAtLeast(0L))
            while (history.size > HISTORY_SIZE) history.removeAt(0)
            storage.encode(KEY_UP, upPerSec)
            storage.encode(KEY_DOWN, downPerSec)
            storage.encode(KEY_HISTORY, history.joinToString(","))
            storage.encode(KEY_UPDATED_AT, System.currentTimeMillis())
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to publish live speed", e)
        }
    }

    fun clear() {
        try {
            storage.clearAll()
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to clear live speed", e)
        }
    }

    fun read(): Sample {
        return try {
            Sample(
                up = storage.decodeLong(KEY_UP, 0L),
                down = storage.decodeLong(KEY_DOWN, 0L),
                history = readHistory(),
                updatedAt = storage.decodeLong(KEY_UPDATED_AT, 0L),
            )
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to read live speed", e)
            Sample(0L, 0L, emptyList(), 0L)
        }
    }

    private fun readHistory(): List<Long> {
        val raw = storage.decodeString(KEY_HISTORY, "").orEmpty()
        if (raw.isBlank()) return emptyList()
        return raw.split(",").mapNotNull { it.trim().toLongOrNull() }
    }
}
