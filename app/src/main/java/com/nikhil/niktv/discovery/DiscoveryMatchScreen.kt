package com.nikhil.niktv.discovery

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.nikhil.niktv.data.artworkRequest
import com.nikhil.niktv.model.*
import com.nikhil.niktv.ui.*

/** The existing matching state/actions, inside the same navigation shell as discovery. */
@Composable
internal fun DiscoveryMatchScreen(app: NikTvState, close: () -> Unit,
    selectSurface: (DashboardSurface) -> Unit, navigate: (String) -> Unit,
    chooseMovie: (MediaItem) -> Unit, chooseSeries: (MediaItem) -> Unit) {
    val configuration = LocalConfiguration.current
    val context = LocalContext.current
    val phone = usesDiscoveryPhoneLayout(configuration.smallestScreenWidthDp, context.isTvLikeDevice(configuration))
    val series = app.movieMatchSelection == null
    val title = app.movieMatchSelection?.title ?: app.seriesMatchSelection?.name.orEmpty()
    val year = app.movieMatchSelection?.releaseYear ?: app.seriesMatchSelection?.firstAirYear
    val candidates = if (series) app.seriesMatchCandidates else app.movieMatchCandidates
    val loading = !series && app.movieMatchLoadingMore
    val type = if (series) CatalogType.SERIES else CatalogType.MOVIES
    val categories = remember(app.rawCategoriesByType, type) {
        app.rawCategoriesByType[type].orEmpty().associate { it.id to it.title }
    }
    val columns = if (phone) 1 else 2
    val header = remember(app.savedProfile?.cacheKey(), title) { FocusRequester() }
    val first = remember(app.savedProfile?.cacheKey(), title) { FocusRequester() }
    val remote = context.usesRemoteNavigation(configuration) || LocalInputModeManager.current.inputMode == androidx.compose.ui.input.InputMode.Keyboard
    var menu by remember { mutableStateOf(false) }
    val surface = app.discoverySurface ?: if (series) DashboardSurface.SERIES else DashboardSurface.MOVIES
    Column(Modifier.fillMaxSize().background(Color(0xFF090B10)).safeDrawingPadding()
        .initialFocusEntry(if (candidates.isEmpty()) header else first, remote)) {
        if (phone) DiscoveryPhoneNavigation(surface, header, first, close, selectSurface, navigate,
            menu, { menu = it }, profile = app.savedProfile?.name.orEmpty())
        else DiscoveryTopNavigation(surface, app.savedProfile?.name.orEmpty(), header, first,
            close, selectSurface, navigate, {}, menu, { menu = it }, null)
        Column(Modifier.fillMaxWidth().padding(horizontal = if (phone) 16.dp else 24.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text("Choose IPTV version", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            Text(listOfNotNull(title.takeIf { it.isNotBlank() }, year?.toString(), "${candidates.size} matches").joinToString(" · "),
                color = Color(0xFFA7AFBD), fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        StableTileFocusViewport {
            LazyVerticalGrid(GridCells.Fixed(columns), modifier = Modifier.fillMaxWidth().weight(1f),
                contentPadding = PaddingValues(horizontal = if (phone) 16.dp else 24.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                itemsIndexed(candidates, key = { _, item -> item.id }) { index, item ->
                    DiscoveryMatchCard(item, categories[item.portalCategoryId], phone,
                        Modifier.then(if (index == 0) Modifier.focusRequester(first) else Modifier)
                            .focusProperties { if (index < columns) up = header },
                        select = { if (series) chooseSeries(item) else chooseMovie(item) })
                }
                if (loading) item("loading", span = { GridItemSpan(maxLineSpan) }) {
                    Row(Modifier.fillMaxWidth().padding(10.dp), horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp)); Text("Finding more matches…", color = Color(0xFFA7AFBD), fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun DiscoveryMatchCard(item: MediaItem, category: String?, phone: Boolean,
    modifier: Modifier, select: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Surface(onClick = select, modifier = modifier.fillMaxWidth().heightIn(min = if (phone) 84.dp else 80.dp)
        .onFocusChanged { focused = it.isFocused }, shape = RoundedCornerShape(10.dp),
        color = if (focused) Color(0xFF29313E) else Color(0xFF171B24),
        border = BorderStroke(if (focused) 2.dp else 1.dp, if (focused) Color.White else Color(0xFF343A47))) {
        Box {
            Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                AsyncImage(artworkRequest(LocalContext.current, item), null,
                    Modifier.width(if (phone) 42.dp else 36.dp).height(if (phone) 63.dp else 54.dp)
                        .clip(RoundedCornerShape(5.dp)).background(Color(0xFF252B36)), contentScale = ContentScale.Crop)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    // Keep provider language/quality qualifiers: they are the reason for choosing a version.
                    Text(item.title, color = Color.White, fontSize = 14.sp, lineHeight = 18.sp,
                        fontWeight = FontWeight.SemiBold, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    category?.takeIf { it.isNotBlank() }?.let {
                        Text(it, color = Color(0xFFA7AFBD), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                Icon(Icons.Default.PlayArrow, null, Modifier.size(20.dp), tint = if (focused) Color.White else Color(0xFFA7AFBD))
            }
            if (focused) Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 12.dp)
                .height(3.dp).background(MaterialTheme.colorScheme.primary))
        }
    }
}
