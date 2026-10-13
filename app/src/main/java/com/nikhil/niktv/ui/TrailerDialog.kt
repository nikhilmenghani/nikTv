package com.nikhil.niktv.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.nikhil.niktv.data.TmdbClient
import com.nikhil.niktv.model.MediaItem
import kotlinx.coroutines.CancellationException

@Composable
internal fun TrailerDialog(item: MediaItem, series: Boolean, launch: (String) -> Unit, dismiss: () -> Unit) {
    val context = LocalContext.current
    var key by remember(item.id) { mutableStateOf<String?>(null) }
    var loading by remember(item.id) { mutableStateOf(true) }
    var message by remember(item.id) { mutableStateOf<String?>(null) }
    val openWithRequester = remember(item.id) { FocusRequester() }
    LaunchedEffect(item.id, series) {
        try {
            key = TmdbClient().trailerKey(item, series)
            key?.let { videoKey ->
                launch(videoKey)
                dismiss()
                return@LaunchedEffect
            }
            if (key == null) message = "No listed trailer found. You can search YouTube for this title."
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { message = "Trailer lookup failed. You can still search YouTube." }
        finally { loading = false }
    }
    val uri = key?.let { Uri.parse("https://www.youtube.com/watch?v=$it") }
        ?: Uri.parse("https://www.youtube.com/results?search_query=" + Uri.encode(item.title + " official trailer"))
    val apps = remember(uri) {
        context.packageManager.queryIntentActivities(Intent(Intent.ACTION_VIEW, uri), 0)
            .filter { info ->
                val name = info.activityInfo.packageName.lowercase()
                name.contains("youtube") || name.contains("smarttube") || name.contains("videomanager")
            }.distinctBy { it.activityInfo.packageName }
    }
    fun open(packageName: String? = null) {
        try {
            val video = Intent(Intent.ACTION_VIEW, uri).apply {
                if (packageName != null) setPackage(packageName)
            }
            context.startActivity(if (packageName == null) Intent.createChooser(video, "Play trailer externally") else video)
        } catch (_: Exception) { message = "Could not open that app. Try another option." }
    }
    Dialog(onDismissRequest = dismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val inputMode = LocalInputModeManager.current.inputMode
        val windowFocused = LocalWindowInfo.current.isWindowFocused
        LaunchedEffect(loading, item.id, inputMode, windowFocused) {
            if (loading || !windowFocused) return@LaunchedEffect
            repeat(8) {
                withFrameNanos { }
                if (runCatching { openWithRequester.requestFocus() }.getOrDefault(false)) return@LaunchedEffect
                kotlinx.coroutines.delay(40L)
            }
        }
        Surface(shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp),
            color = androidx.compose.ui.graphics.Color(0xFF181818),
            modifier = Modifier.widthIn(max = 720.dp).fillMaxWidth(.9f)) {
            Column(Modifier.padding(20.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(item.title, style = MaterialTheme.typography.titleLarge)
                if (loading) Text("Finding trailer...")
                message?.let { Text(it, color = androidx.compose.ui.graphics.Color.LightGray) }
                if (!loading) {
                    key?.let { videoKey ->
                        NikTvPrimaryActionButton(onClick = {
                            launch(videoKey)
                            dismiss()
                        }, modifier = Modifier.fillMaxWidth()) { Text("Watch in NikTV") }
                    }
                    apps.forEach { info ->
                        NikTvSecondaryActionButton(onClick = { open(info.activityInfo.packageName) },
                            modifier = Modifier.fillMaxWidth()) {
                            Text("${if (key == null) "Search" else "Watch"} in ${info.loadLabel(context.packageManager)}")
                        }
                    }
                    NikTvSecondaryActionButton(onClick = { open() },
                        modifier = Modifier.fillMaxWidth().focusRequester(openWithRequester)) {
                        Text(if (key == null) "Search YouTube" else "Open with...")
                    }
                }
                NikTvSecondaryActionButton(onClick = dismiss, modifier = Modifier.fillMaxWidth()) { Text("Close") }
            }
        }
    }
}
