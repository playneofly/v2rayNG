package com.v2ray.ang.handler

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.core.CoreServiceManager
import com.v2ray.ang.core.LauncherManager
import com.v2ray.ang.extension.toast
import com.v2ray.ang.helper.NotificationHelper
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * FILTERNET: periodic health check of the active connection.
 *
 * While the tunnel is up a lightweight probe runs on the configured interval.
 * After [failureThreshold] consecutive failures the tunnel is restarted on the
 * server with the best stored delay, so a dead server heals itself.
 *
 * Disabled by default; the user turns it on in Settings.
 */
object AutoTestScheduler {

    private const val WORK_NAME = "filternet_auto_health_check"
    private const val CHANNEL_ID = "filternet_auto_switch"
    private const val NOTIFICATION_ID = 7301

    const val DEFAULT_INTERVAL_MINUTES = 15
    const val DEFAULT_FAILURE_THRESHOLD = 2
    private const val MIN_INTERVAL_MINUTES = 15L

    @Volatile
    private var consecutiveFailures = 0

    fun isEnabled(): Boolean =
        MmkvManager.decodeSettingsBool(AppConfig.PREF_FN_AUTO_TEST_ENABLED, false)

    fun intervalMinutes(): Long {
        val stored = MmkvManager.decodeSettingsString(AppConfig.PREF_FN_AUTO_TEST_INTERVAL)
            ?.toLongOrNull() ?: DEFAULT_INTERVAL_MINUTES.toLong()
        val saverFactor = if (MmkvManager.decodeSettingsBool(AppConfig.PREF_FN_BATTERY_SAVER) == true) 2 else 1
        return (stored * saverFactor).coerceAtLeast(MIN_INTERVAL_MINUTES)
    }

    fun failureThreshold(): Int =
        MmkvManager.decodeSettingsString(AppConfig.PREF_FN_AUTO_TEST_FAILURES)
            ?.toIntOrNull()?.coerceAtLeast(1) ?: DEFAULT_FAILURE_THRESHOLD

    /**
     * Enables or cancels the periodic worker to match the current setting.
     */
    fun sync(context: Context) {
        try {
            val wm = WorkManager.getInstance(context.applicationContext)
            if (!isEnabled()) {
                wm.cancelUniqueWork(WORK_NAME)
                consecutiveFailures = 0
                return
            }
            val request = PeriodicWorkRequestBuilder<HealthCheckTask>(
                intervalMinutes(), TimeUnit.MINUTES
            )
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .addTag(WORK_NAME)
                .build()
            wm.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
            LogUtil.i(AppConfig.TAG, "AutoTestScheduler: enabled, every ${intervalMinutes()} min")
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "AutoTestScheduler sync failed", e)
        }
    }

    class HealthCheckTask(context: Context, params: WorkerParameters) :
        CoroutineWorker(context, params) {

        override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
            if (!isEnabled()) return@withContext Result.success()
            if (!CoreServiceManager.isRunning()) {
                consecutiveFailures = 0
                return@withContext Result.success()
            }

            val healthy = probe()
            if (healthy) {
                consecutiveFailures = 0
                return@withContext Result.success()
            }

            consecutiveFailures++
            LogUtil.w(AppConfig.TAG, "AutoTestScheduler: probe failed ($consecutiveFailures)")
            if (consecutiveFailures < failureThreshold()) return@withContext Result.success()

            consecutiveFailures = 0
            switchToBestServer()
            Result.success()
        }

        /**
         * Simple reachability probe through the active tunnel.
         */
        private fun probe(): Boolean = try {
            val url = java.net.URL(SettingsManager.getDelayTestUrl())
            val port = if (url.protocol == "https") 443 else 80
            SpeedtestManager.socketConnectTime(url.host, port, 4000) >= 0
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "AutoTestScheduler probe error", e)
            false
        }

        /**
         * Picks the stored-best server different from the current one and restarts on it.
         */
        private fun switchToBestServer() {
            try {
                val current = MmkvManager.getSelectServer()
                val candidate = MmkvManager.decodeAllServerList()
                    .filter { it != current }
                    .mapNotNull { guid ->
                        val delay = MmkvManager.decodeServerAffiliationInfo(guid)?.testDelayMillis ?: 0L
                        if (delay > 0L) guid to delay else null
                    }
                    .minByOrNull { it.second }
                    ?.first ?: return

                val name = MmkvManager.decodeServerConfig(candidate)?.remarks.orEmpty()
                MmkvManager.setSelectServer(candidate)
                LauncherManager.restartService(applicationContext)

                val message = applicationContext.getString(R.string.fn_auto_switched, name)
                notify(message)
                applicationContext.toast(message)
                LogUtil.i(AppConfig.TAG, "AutoTestScheduler: switched to $name")
            } catch (e: Exception) {
                LogUtil.e(AppConfig.TAG, "AutoTestScheduler switch failed", e)
            }
        }

        private fun notify(message: String) {
            try {
                NotificationHelper.ensureNotificationChannel(
                    context = applicationContext,
                    channelId = CHANNEL_ID,
                    channelNameRes = R.string.fn_pref_auto_test,
                    importance = android.app.NotificationManager.IMPORTANCE_DEFAULT,
                ) {}
                val notification = androidx.core.app.NotificationCompat
                    .Builder(applicationContext, CHANNEL_ID)
                    .setSmallIcon(com.v2ray.ang.R.drawable.ic_stat_name)
                    .setContentTitle(applicationContext.getString(R.string.app_name))
                    .setContentText(message)
                    .setAutoCancel(true)
                    .build()
                androidx.core.app.NotificationManagerCompat.from(applicationContext)
                    .notify(NOTIFICATION_ID, notification)
            } catch (e: Exception) {
                LogUtil.e(AppConfig.TAG, "AutoTestScheduler notify failed", e)
            }
        }
    }
}
