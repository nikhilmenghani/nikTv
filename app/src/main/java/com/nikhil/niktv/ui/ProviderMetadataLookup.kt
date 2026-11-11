package com.nikhil.niktv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.*
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.*
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.nikhil.niktv.data.providerTmdbTitle
import com.nikhil.niktv.data.providerSeriesTmdbTitle
import com.nikhil.niktv.discovery.DiscoveryCard
import com.nikhil.niktv.discovery.DiscoveryRepository
import com.nikhil.niktv.model.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

internal val LocalOpenProviderMetadataLookup = staticCompositionLocalOf<((MediaItem, CatalogType) -> Unit)?> { null }

@Composable
internal fun ProviderMetadataLookupMenuItem(item: MediaItem, type: CatalogType? = null, dismiss: () -> Unit) {
    val open = LocalOpenProviderMetadataLookup.current ?: return
    val resolved = LocalRecommendationTypeFor.current?.invoke(item, type) ?: type
    if (resolved !in setOf(CatalogType.MOVIES, CatalogType.SERIES) || item.id.startsWith("tmdb-") ||
        item.isAdult || item.episodeNumber != null) return
    NikDropdownMenuItem(text = { Text("Find TMDB details") }, leadingIcon = { Icon(Icons.Default.Search, null) },
        onClick = { dismiss(); open(item, requireNotNull(resolved)) })
}

/** Only an explicit tile action searches. A user chooses the identity; remakes are never guessed. */
@Composable
internal fun ProviderMetadataLookupDialog(item: MediaItem, type: CatalogType,
    confirm: suspend (DiscoveryCard) -> Unit, close: () -> Unit) {
    val context = LocalContext.current
    val repository = remember(context) { DiscoveryRepository(context) }
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    val remote = context.usesRemoteNavigation(LocalConfiguration.current)
    var query by remember(item.id, type) { mutableStateOf(if (type == CatalogType.SERIES)
        providerSeriesTmdbTitle(item.title) else providerTmdbTitle(item.title)) }
    var results by remember { mutableStateOf(emptyList<DiscoveryCard>()) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val search = remember { FocusRequester() }
    suspend fun fetch() {
        if (query.isBlank()) return
        keyboard?.hide(); busy = true; message = null
        try {
            results = repository.providerTitleMatches(query, type)
            if (results.isEmpty()) message = "No matching titles. Edit the extracted title and try again."
        } catch (cancelled: CancellationException) { throw cancelled }
          catch (_: Exception) { message = if (!repository.configured) "TMDB is not configured on this device." else "Could not reach TMDB. Try again." }
        finally { busy = false }
    }
    LaunchedEffect(Unit) { fetch(); withFrameNanos { }; runCatching { search.requestFocus() } }
    Dialog(onDismissRequest = close) {
        Surface(shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp), color = NikColors.surface,
            modifier = Modifier.widthIn(max = 620.dp).fillMaxWidth().heightIn(max = (LocalConfiguration.current.screenHeightDp - 48).dp)) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Find TMDB details", style = MaterialTheme.typography.titleLarge)
                Text("Choose the correct title and year.", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(query, { query = it }, label = { Text("Extracted title") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth().onPreviewKeyEvent { event ->
                        if (remote && event.key in menuActivationKeys) {
                            if (event.type == KeyEventType.KeyUp) keyboard?.show()
                            true
                        } else false
                    }, keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search, showKeyboardOnFocus = !remote),
                    keyboardActions = KeyboardActions(onSearch = { scope.launch { fetch() } }))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NikTvSecondaryActionButton(close, Modifier.weight(1f)) { Text("Close") }
                    NikTvPrimaryActionButton({ scope.launch { fetch() } }, Modifier.weight(1f).focusRequester(search),
                        enabled = !busy && query.isNotBlank()) { Text(if (busy) "Searching…" else "Search") }
                }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                results.forEach { card ->
                    NikTvSecondaryActionButton({
                        scope.launch {
                            busy = true
                            try { confirm(card); close() }
                            catch (cancelled: CancellationException) { throw cancelled }
                            catch (_: Exception) { message = "Could not save this match. Try again." }
                            finally { busy = false }
                        }
                    }, Modifier.fillMaxWidth(), enabled = !busy) {
                        Column(Modifier.weight(1f)) {
                            Text(card.title, style = MaterialTheme.typography.bodyLarge)
                            Text(listOfNotNull(card.date?.take(4), card.rating?.let { "★ $it" }).joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
}
