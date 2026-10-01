package com.v2ray.ang.service

import android.app.PendingIntent
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.core.CoreServiceManager
import com.v2ray.ang.core.LauncherManager
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.helper.NotificationHelper
import com.v2ray.ang.ui.main.MainActivity
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * FILTERNET: turns the VPN on by itself when a watched app is opened.
 *
 * Android gives no callback for "another app came to the foreground", so the
 * only workable route without accessibility access is polling UsageStats. The
 * poll is deliberately cheap - one query over the last few seconds - and the
 * service stops itself the moment the feature is switched off.
 *
 * Requires the user to grant "usage access", which is a settings-screen toggle.
 */
class AppTriggerService : Service() {

    private var job: Job? = null
    private val scope = CoroutineScope(Dispatchers.Default)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIFICATION_ID, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (job?.isActive == true) return START_STICKY
        job = scope.launch {
            var lastTrigger = 0L
            while (isActive) {
                try {
                    if (!isEnabled(applicationContext)) {
                        stopSelf()
                        return@launch
                    }
                    val watched = watchedPackages(applicationContext)
                    if (watched.isNotEmpty() && !CoreServiceManager.isRunning()) {
                        val fg = foregroundPackage(applicationContext)
                        if (fg != null && fg in watched) {
                            val now = System.currentTimeMillis()
                            if (now - lastTrigger > TRIGGER_COOLDOWN_MS) {
                                lastTrigger = now
                                LogUtil.i(AppConfig.TAG, "AppTrigger: $fg opened, connecting")
                                LauncherManager.startService(applicationContext)
                            }
                        }
                    }
                } catch (e: Exception) {
                    LogUtil.e(AppConfig.TAG, "AppTrigger loop error", e)
                }
                delay(POLL_INTERVAL_MS)
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        job?.cancel()
        job = null
        super.onDestroy()
    }

    private fun buildNotification(): android.app.Notification {
        NotificationHelper.ensureNotificationChannel(
            context = this,
            channelId = CHANNEL_ID,
            channelNameRes = R.string.fn_trigger_title,
            importance = android.app.NotificationManager.IMPORTANCE_MIN,
        ) {}
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_name)
            .setContentTitle(getString(R.string.fn_trigger_running))
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(pi)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "filternet_app_trigger"
        private const val NOTIFICATION_ID = 7311
        private const val POLL_INTERVAL_MS = 1500L

        /** Stops a flapping app from re-triggering a connect over and over. */
        private const val TRIGGER_COOLDOWN_MS = 20_000L

        fun isEnabled(context: Context): Boolean =
            MmkvManager.decodeSettingsBool(AppConfig.PREF_FN_APP_TRIGGER_ENABLED, false)

        fun watchedPackages(context: Context): Set<String> =
            runCatching {
                MmkvManager.decodeSettingsStringSet(AppConfig.PREF_FN_APP_TRIGGER_APPS)
                    ?.toSet().orEmpty()
            }.getOrDefault(emptySet())

        /** True when the user has granted "usage access" in system settings. */
        fun hasUsagePermission(context: Context): Boolean = runCatching {
            val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
                ?: return@runCatching false
            val now = System.currentTimeMillis()
            val events = usm.queryEvents(now - 60_000, now)
            // If permission is missing the query simply yields nothing.
            var any = false
            val e = UsageEvents.Event()
            while (events.hasNextEvent()) {
                events.getNextEvent(e)
                any = true
                break
            }
            any
        }.getOrDefault(false)

        private fun foregroundPackage(context: Context): String? = runCatching {
            val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
                ?: return@runCatching null
            val now = System.currentTimeMillis()
            val events = usm.queryEvents(now - 6_000, now)
            var last: String? = null
            val e = UsageEvents.Event()
            while (events.hasNextEvent()) {
                events.getNextEvent(e)
                val isResume = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    e.eventType == UsageEvents.Event.ACTIVITY_RESUMED
                } else {
                    @Suppress("DEPRECATION")
                    e.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND
                }
                if (isResume) last = e.packageName
            }
            last
        }.getOrNull()

        /** Starts or stops the watcher so it matches the current setting. */
        fun sync(context: Context) = runCatching {
            val ctx = context.applicationContext
            val intent = Intent(ctx, AppTriggerService::class.java)
            if (isEnabled(ctx) && watchedPackages(ctx).isNotEmpty() && hasUsagePermission(ctx)) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    ctx.startForegroundService(intent)
                } else {
                    ctx.startService(intent)
                }
            } else {
                ctx.stopService(intent)
            }
        }.onFailure { LogUtil.e(AppConfig.TAG, "AppTrigger: sync failed", it) }.let { }
    }
}
