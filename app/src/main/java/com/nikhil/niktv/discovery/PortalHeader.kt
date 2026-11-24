package com.nikhil.niktv.discovery

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material3.DropdownMenu
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.nikhil.niktv.R
import com.nikhil.niktv.ui.isTvLikeDevice
import com.nikhil.niktv.ui.usesShortWideLayout

@Composable
internal fun PortalWideHeader(profile: String, openProfile: () -> Unit, modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit) {
    val configuration = LocalConfiguration.current
    val short = configuration.usesShortWideLayout(LocalContext.current.isTvLikeDevice(configuration))
    val fontScale = LocalDensity.current.fontScale
    val height = if (short) maxOf(46.dp, (20f * fontScale + 22f).dp)
        else maxOf(56.dp, (24f * fontScale + 24f).dp)
    val profileWithoutClock = discoveryProfileHeaderWidth(clock = false)
    val profileWithClock = if (short) profileWithoutClock else discoveryProfileHeaderWidth(clock = true)
    val gap = 12.dp
    BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = if (short) 12.dp else 24.dp,
        vertical = if (short) 6.dp else 12.dp).then(modifier)) {
        val profileWidth = profileWithClock.coerceAtMost((maxWidth * .4f).coerceAtLeast(profileWithoutClock))
        val menuWidth = (maxWidth - profileWidth - gap).coerceAtLeast(0.dp)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            DiscoveryGlassPanel(Modifier.widthIn(max = menuWidth).height(height), null, Rect.Zero) {
                CompositionLocalProvider(LocalDiscoveryGlassButtons provides true, LocalDiscoveryHeaderActionSizing provides true) {
                    Row(Modifier.fillMaxHeight().padding(5.dp), horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(discoveryHeaderActionSize()), contentAlignment = Alignment.Center) {
                            Image(painterResource(R.drawable.niktv_logo_foreground), "NikTV", Modifier.fillMaxSize()
                                .graphicsLayer { scaleX = 1.8f; scaleY = 1.8f })
                        }
                        content()
                    }
                }
            }
            Spacer(Modifier.weight(1f).widthIn(min = gap))
            DiscoveryClockProfile(profile, Modifier.width(profileWidth).height(height), null, Rect.Zero, openProfile)
        }
    }
}

@Composable
internal fun PortalPhoneHeader(content: @Composable RowScope.() -> Unit) {
    CompositionLocalProvider(LocalMobileGlassEnabled provides true) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically,
            content = content)
    }
}

@Composable
internal fun PortalPhoneDock(modifier: Modifier, content: @Composable RowScope.() -> Unit) {
    DiscoveryGlassPanel(modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth(),
        artwork = LocalMobileGlassArtwork.current, backdropBounds = LocalMobileGlassBounds.current,
        fitArtwork = LocalMobileGlassFit.current, topAligned = LocalMobileGlassTopAligned.current,
        fillWidth = LocalMobileGlassFillWidth.current) {
        Row(Modifier.padding(5.dp).selectableGroup(), horizontalArrangement = Arrangement.spacedBy(2.dp), content = content)
    }
}

@Composable
internal fun PortalOptionsButton(label: String, modifier: Modifier = Modifier, selected: Boolean = false, open: () -> Unit) {
    DiscoveryAction(label, Icons.Default.MoreHoriz, iconOnly = true, selected = selected,
        modifier = modifier, focusHint = false, onClick = open)
}

@Composable
internal fun PortalOptionsMenu(expanded: Boolean, close: () -> Unit, modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit) {
    DropdownMenu(expanded, close, modifier = modifier, containerColor = Color(0xEE171B24),
        shape = RoundedCornerShape(10.dp), content = content)
}
