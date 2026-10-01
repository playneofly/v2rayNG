package com.v2ray.ang.handler

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import com.v2ray.ang.AppConfig
import com.v2ray.ang.util.LogUtil

/**
 * FILTERNET: the launcher icon turns green while the tunnel is up.
 *
 * Android has no API for "change my icon"; the trick is two activity-aliases
 * pointing at the same activity, with exactly one enabled at a time.
 *
 * It is off by default on purpose. Swapping an alias makes some launchers drop
 * the icon from the home screen for a moment, and a few aggressive launchers
 * lose a pinned shortcut entirely - not a surprise worth forcing on everyone.
 */
object DynamicIconManager {

    private const val ALIAS_DEFAULT = "com.v2ray.ang.ui.main.MainActivityDefaultAlias"
    private const val ALIAS_CONNECTED = "com.v2ray.ang.ui.main.MainActivityConnectedAlias"

    fun isEnabled(): Boolean =
        runCatching {
            MmkvManager.decodeSettingsBool(AppConfig.PREF_FN_DYNAMIC_ICON, false)
        }.getOrDefault(false)

    /**
     * Applies the icon that matches the current state.
     * Does nothing at all when the user has not opted in.
     */
    fun apply(context: Context, connected: Boolean) {
        if (!isEnabled()) {
            // Make sure we never leave the green icon behind after opting out.
            runCatching { enableOnly(context, ALIAS_DEFAULT) }
            return
        }
        runCatching {
            enableOnly(context, if (connected) ALIAS_CONNECTED else ALIAS_DEFAULT)
        }.onFailure { LogUtil.e(AppConfig.TAG, "DynamicIcon: apply failed", it) }
    }

    private fun enableOnly(context: Context, alias: String) {
        val pm = context.packageManager
        val pkg = context.packageName
        val all = listOf(ALIAS_DEFAULT, ALIAS_CONNECTED)
        for (a in all) {
            val state = if (a == alias) {
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            } else {
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            }
            val component = ComponentName(pkg, a)
            if (pm.getComponentEnabledSetting(component) == state) continue
            pm.setComponentEnabledSetting(component, state, PackageManager.DONT_KILL_APP)
        }
    }
}
