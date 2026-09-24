package com.v2ray.ang

import android.app.Application
import android.content.Context
import androidx.core.content.ContextCompat
import androidx.work.Configuration
import androidx.work.WorkManager
import com.v2ray.ang.AppConfig.ANG_PACKAGE
import com.v2ray.ang.handler.AppLocaleManager
import com.v2ray.ang.handler.FilternetCrashHandler
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.ui.compose.ThemeManager

class AngApplication : Application() {
    companion object {
        lateinit var application: AngApplication
    }

    /**
     * Attaches the base context to the application.
     * @param base The base context.
     */
    override fun attachBaseContext(base: Context?) {
        super.attachBaseContext(base?.let(ContextCompat::getContextForLanguage))
        application = this
    }

    private val workManagerConfiguration: Configuration = Configuration.Builder()
        .setDefaultProcessName("${ANG_PACKAGE}:bg")
        .build()

    /**
     * Initializes the application.
     */
    override fun onCreate() {
        super.onCreate()

        // FILTERNET: must be the very first thing, so any later failure is recorded
        // and shown to the user instead of a bare "keeps stopping" system dialog.
        FilternetCrashHandler.install(this)

        MmkvManager.initialize(this)

        AppLocaleManager.initialize(this)

        // FILTERNET: WorkManager throws when something already initialized it
        // (App Startup, another process, a restart of the same process). That must
        // never take the whole app down.
        runCatching { WorkManager.initialize(this, workManagerConfiguration) }

        // Ensure critical preference defaults are present in MMKV early
        runCatching { SettingsManager.initApp(this) }

        // Initialize theme state from MMKV
        runCatching { ThemeManager.refresh() }
    }
}
