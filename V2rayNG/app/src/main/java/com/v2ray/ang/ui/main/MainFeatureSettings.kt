package com.v2ray.ang.ui.main

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.service.AppTriggerService
import com.v2ray.ang.ui.compose.FilternetTokens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * FILTERNET: the settings for the two features that need more than a switch -
 * auto-connect on app launch, and the colour-changing launcher icon.
 */
@Composable
internal fun FeatureSettingsScreen() {
    val context = LocalContext.current

    var triggerOn by remember {
        mutableStateOf(MmkvManager.decodeSettingsBool(AppConfig.PREF_FN_APP_TRIGGER_ENABLED, false))
    }
    var dynamicIcon by remember {
        mutableStateOf(MmkvManager.decodeSettingsBool(AppConfig.PREF_FN_DYNAMIC_ICON, false))
    }
    var hasUsage by remember { mutableStateOf(AppTriggerService.hasUsagePermission(context)) }
    var chosen by remember { mutableStateOf(AppTriggerService.watchedPackages(context)) }
    var apps by remember { mutableStateOf<List<AppEntry>>(emptyList()) }

    LaunchedEffect(Unit) {
        apps = withContext(Dispatchers.IO) { loadLaunchableApps(context) }
    }
    // Permission is granted on a system screen, so re-check whenever we resume.
    LaunchedEffect(triggerOn) {
        hasUsage = AppTriggerService.hasUsagePermission(context)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
    ) {
        Spacer(Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.fn_trigger_title),
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = stringResource(R.string.fn_trigger_sub),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(14.dp))

        ToggleRow(
            title = stringResource(R.string.fn_trigger_title),
            subtitle = if (chosen.isEmpty()) stringResource(R.string.fn_trigger_none)
            else stringResource(R.string.fn_trigger_pick),
            checked = triggerOn,
            onChange = {
                triggerOn = it
                MmkvManager.encodeSettings(AppConfig.PREF_FN_APP_TRIGGER_ENABLED, it)
                AppTriggerService.sync(context)
            },
        )

        if (triggerOn && !hasUsage) {
            Spacer(Modifier.height(8.dp))
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        runCatching {
                            context.startActivity(
                                Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        }
                    },
                shape = RoundedCornerShape(FilternetTokens.RadiusMedium),
                color = FilternetTokens.Amber.copy(alpha = 0.12f),
                contentColor = FilternetTokens.Amber,
            ) {
                Column(Modifier.padding(13.dp)) {
                    Text(
                        text = stringResource(R.string.fn_trigger_permission),
                        style = MaterialTheme.typography.titleSmall,
                        color = FilternetTokens.Amber,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = stringResource(R.string.fn_trigger_permission_sub),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        Spacer(Modifier.height(10.dp))

        ToggleRow(
            title = stringResource(R.string.fn_dynamic_icon),
            subtitle = stringResource(R.string.fn_dynamic_icon_sub),
            checked = dynamicIcon,
            onChange = {
                dynamicIcon = it
                MmkvManager.encodeSettings(AppConfig.PREF_FN_DYNAMIC_ICON, it)
            },
        )

        if (triggerOn) {
            Spacer(Modifier.height(14.dp))
            Text(
                text = stringResource(R.string.fn_trigger_pick),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(6.dp))
            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(apps, key = { it.packageName }) { app ->
                    val isOn = app.packageName in chosen
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                val next = chosen.toMutableSet()
                                if (isOn) next.remove(app.packageName) else next.add(app.packageName)
                                chosen = next
                                MmkvManager.encodeSettings(
                                    AppConfig.PREF_FN_APP_TRIGGER_APPS, next.toMutableSet()
                                )
                                AppTriggerService.sync(context)
                            }
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = isOn, onCheckedChange = null)
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = app.label,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(FilternetTokens.RadiusMedium),
        color = MaterialTheme.colorScheme.surfaceContainer,
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(10.dp))
            Switch(checked = checked, onCheckedChange = onChange)
        }
    }
}

internal data class AppEntry(val packageName: String, val label: String)

private fun loadLaunchableApps(context: Context): List<AppEntry> = runCatching {
    val pm = context.packageManager
    pm.getInstalledApplications(0)
        .asSequence()
        .filter { pm.getLaunchIntentForPackage(it.packageName) != null }
        .filter { it.packageName != context.packageName }
        .filter { (it.flags and ApplicationInfo.FLAG_SYSTEM) == 0 || pm.getLaunchIntentForPackage(it.packageName) != null }
        .map { AppEntry(it.packageName, pm.getApplicationLabel(it).toString()) }
        .distinctBy { it.packageName }
        .sortedBy { it.label.lowercase() }
        .toList()
}.getOrDefault(emptyList())
