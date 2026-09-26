package com.v2ray.ang.ui.main

import androidx.annotation.StringRes
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.v2ray.ang.R
import com.v2ray.ang.ui.compose.FilternetGlassBorder
import com.v2ray.ang.ui.compose.FilternetGlassColor
import com.v2ray.ang.ui.compose.MainNavigationGlyph
import com.v2ray.ang.ui.compose.MainNavigationGlyphType

internal enum class MainRootTab(
    @StringRes val labelRes: Int,
    val glyph: MainNavigationGlyphType,
) {
    Home(R.string.app_name, MainNavigationGlyphType.Home),
    Servers(R.string.title_server, MainNavigationGlyphType.Servers),
    Stats(R.string.fn_traffic_title, MainNavigationGlyphType.Stats),
    Settings(R.string.title_settings, MainNavigationGlyphType.Settings),
}

@Composable
internal fun MainNavigationBar(
    selectedTab: MainRootTab,
    onSelect: (MainRootTab) -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.Transparent)
            .navigationBarsPadding()
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp),
            color = FilternetGlassColor,
        contentColor = MaterialTheme.colorScheme.onSurface,
            shape = RoundedCornerShape(26.dp),
            border = FilternetGlassBorder,
            shadowElevation = 14.dp,
        ) {
            NavigationBar(
                modifier = Modifier.fillMaxWidth(),
                containerColor = Color.Transparent,
                contentColor = MaterialTheme.colorScheme.onBackground,
                tonalElevation = 0.dp,
            ) {
                MainRootTab.entries.forEach { tab ->
                    val selected = selectedTab == tab
                    val indicatorWidth by animateDpAsState(
                        targetValue = if (selected) 54.dp else 0.dp,
                        label = "main-navigation-indicator",
                    )
                    NavigationBarItem(
                        selected = selected,
                        onClick = { onSelect(tab) },
                        icon = {
                            Box(contentAlignment = Alignment.Center) {
                                if (selected) {
                                    Box(
                                        Modifier
                                            .height(34.dp)
                                            .width(indicatorWidth)
                                            .background(
                                                MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
                                                RoundedCornerShape(18.dp),
                                            )
                                    )
                                }
                                MainNavigationGlyph(tab.glyph, selected)
                            }
                        },
                        label = {
                            Text(
                                text = stringResource(tab.labelRes),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                            )
                        },
                        colors = NavigationBarItemDefaults.colors(
                            indicatorColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                            selectedIconColor = MaterialTheme.colorScheme.primary,
                            selectedTextColor = MaterialTheme.colorScheme.primary,
                            unselectedIconColor = MaterialTheme.colorScheme.outline,
                            unselectedTextColor = MaterialTheme.colorScheme.outline,
                        ),
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}