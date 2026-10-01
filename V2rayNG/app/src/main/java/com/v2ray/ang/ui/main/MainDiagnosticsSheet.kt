package com.v2ray.ang.ui.main

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.v2ray.ang.R
import com.v2ray.ang.handler.NetworkDiagnostics
import com.v2ray.ang.ui.compose.FilternetTokens
import com.v2ray.ang.ui.compose.faDigits

/**
 * FILTERNET: "why is it slow?" in one tap.
 *
 * Runs a real chain of probes and translates the outcome into one sentence the
 * user can act on. Every other client just says "connected" and leaves people
 * guessing.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DiagnosticsSheet(
    isRunning: Boolean,
    onDismiss: () -> Unit,
    onFindBetterServer: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var report by remember { mutableStateOf<NetworkDiagnostics.Report?>(null) }

    LaunchedEffect(Unit) {
        report = NetworkDiagnostics.runDiagnostics(isRunning)
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp),
        dragHandle = {
            Box(
                Modifier
                    .padding(top = 10.dp, bottom = 2.dp)
                    .size(width = 42.dp, height = 4.dp)
                    .background(MaterialTheme.colorScheme.outlineVariant, CircleShape)
            )
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 22.dp)
                .padding(bottom = 26.dp),
        ) {
            Text(
                text = stringResource(R.string.fn_diag_title),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(14.dp))

            val r = report
            if (r == null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 26.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        text = stringResource(R.string.fn_diag_running),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                r.checks.forEach { CheckRow(it) }

                Spacer(Modifier.height(16.dp))

                val (verdictRes, tint) = when (r.verdict) {
                    NetworkDiagnostics.Verdict.ALL_GOOD ->
                        R.string.fn_diag_v_ok to FilternetTokens.Mint
                    NetworkDiagnostics.Verdict.NOT_CONNECTED ->
                        R.string.fn_diag_v_off to MaterialTheme.colorScheme.onSurfaceVariant
                    NetworkDiagnostics.Verdict.NATIONAL_INTERNET ->
                        R.string.fn_diag_v_national to FilternetTokens.Amber
                    NetworkDiagnostics.Verdict.TUNNEL_DEAD ->
                        R.string.fn_diag_v_dead to FilternetTokens.Rose
                    NetworkDiagnostics.Verdict.SERVER_SLOW ->
                        R.string.fn_diag_v_slow to FilternetTokens.Amber
                    NetworkDiagnostics.Verdict.PARTIALLY_BLOCKED ->
                        R.string.fn_diag_v_partial to FilternetTokens.Amber
                }

                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(FilternetTokens.RadiusMedium),
                    color = tint.copy(alpha = 0.10f),
                    contentColor = tint,
                ) {
                    Text(
                        text = stringResource(verdictRes),
                        style = MaterialTheme.typography.bodyMedium,
                        color = tint,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                    )
                }

                val offerSwitch = r.verdict == NetworkDiagnostics.Verdict.SERVER_SLOW ||
                    r.verdict == NetworkDiagnostics.Verdict.TUNNEL_DEAD ||
                    r.verdict == NetworkDiagnostics.Verdict.PARTIALLY_BLOCKED
                if (offerSwitch) {
                    Spacer(Modifier.height(10.dp))
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                onDismiss()
                                onFindBetterServer()
                            },
                        shape = RoundedCornerShape(FilternetTokens.RadiusMedium),
                        color = MaterialTheme.colorScheme.primary,
                        contentColor = Color.White,
                    ) {
                        Text(
                            text = stringResource(R.string.fn_diag_switch),
                            style = MaterialTheme.typography.titleSmall,
                            color = Color.White,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(13.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CheckRow(check: NetworkDiagnostics.Check) {
    val labelRes = when (check.id) {
        NetworkDiagnostics.CHECK_TUNNEL -> R.string.fn_diag_tunnel
        NetworkDiagnostics.CHECK_SPEED -> R.string.fn_diag_speed
        NetworkDiagnostics.CHECK_INSTAGRAM -> R.string.fn_svc_instagram
        NetworkDiagnostics.CHECK_YOUTUBE -> R.string.fn_svc_youtube
        NetworkDiagnostics.CHECK_TELEGRAM -> R.string.fn_svc_telegram
        NetworkDiagnostics.CHECK_WHATSAPP -> R.string.fn_svc_whatsapp
        NetworkDiagnostics.CHECK_OPENAI -> R.string.fn_svc_openai
        else -> R.string.fn_svc_google
    }
    val tint = when (check.status) {
        NetworkDiagnostics.Status.OK -> FilternetTokens.Mint
        NetworkDiagnostics.Status.SLOW -> FilternetTokens.Amber
        NetworkDiagnostics.Status.FAIL -> FilternetTokens.Rose
        NetworkDiagnostics.Status.SKIPPED -> MaterialTheme.colorScheme.outline
    }
    val mark = when (check.status) {
        NetworkDiagnostics.Status.OK -> "✓"
        NetworkDiagnostics.Status.SLOW -> "!"
        NetworkDiagnostics.Status.FAIL -> "✕"
        NetworkDiagnostics.Status.SKIPPED -> "–"
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(20.dp)
                .background(tint.copy(alpha = 0.14f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(text = mark, style = MaterialTheme.typography.labelSmall, color = tint)
        }
        Spacer(Modifier.width(10.dp))
        Text(
            text = stringResource(labelRes),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        val detail = when {
            check.id == NetworkDiagnostics.CHECK_SPEED && check.value > 0 ->
                faDigits((check.value * 8 / 1_000_000.0).let { String.format("%.1f", it) }) + " Mb"
            check.id == NetworkDiagnostics.CHECK_TUNNEL && check.value > 0 ->
                faDigits(check.value) + " ms"
            else -> ""
        }
        if (detail.isNotEmpty()) {
            Text(
                text = detail,
                style = MaterialTheme.typography.labelMedium,
                color = tint,
            )
        }
    }
}

/**
 * The compact live checklist that sits on the home screen.
 */
@Composable
internal fun ServiceChecklist(
    results: Map<String, Boolean>?,
    onRefresh: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onRefresh),
        shape = RoundedCornerShape(FilternetTokens.RadiusLarge),
        color = MaterialTheme.colorScheme.surfaceContainer,
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.fn_checklist_title),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.weight(1f))
                Icon(
                    painter = painterResource(R.drawable.ic_refresh_24dp),
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.outline,
                )
            }
            Spacer(Modifier.height(10.dp))
            val order = listOf(
                NetworkDiagnostics.CHECK_INSTAGRAM to R.string.fn_svc_instagram,
                NetworkDiagnostics.CHECK_TELEGRAM to R.string.fn_svc_telegram,
                NetworkDiagnostics.CHECK_YOUTUBE to R.string.fn_svc_youtube,
                NetworkDiagnostics.CHECK_WHATSAPP to R.string.fn_svc_whatsapp,
                NetworkDiagnostics.CHECK_OPENAI to R.string.fn_svc_openai,
                NetworkDiagnostics.CHECK_GOOGLE to R.string.fn_svc_google,
            )
            order.chunked(3).forEach { row ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    row.forEach { (id, labelRes) ->
                        val ok = results?.get(id)
                        Row(
                            modifier = Modifier.weight(1f),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = when (ok) {
                                    true -> "✓"
                                    false -> "✕"
                                    null -> "·"
                                },
                                style = MaterialTheme.typography.labelMedium,
                                color = when (ok) {
                                    true -> FilternetTokens.Mint
                                    false -> FilternetTokens.Rose
                                    null -> MaterialTheme.colorScheme.outline
                                },
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                text = stringResource(labelRes),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
            }
        }
    }
}
