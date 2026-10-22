package com.nikhil.niktv.discovery

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/** Browsing focus never starts editing. Only activation expands the editor. */
@Composable
internal fun DiscoveryCollectionSearch(query: String, change: (String) -> Unit, compact: Boolean, onActivate: () -> Unit = {}) {
    var expanded by remember { mutableStateOf(false) }
    val icon = remember { FocusRequester() }
    val field = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val scope = rememberCoroutineScope()
    val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    var imeSeen by remember { mutableStateOf(false) }
    fun close() {
        imeSeen = false
        expanded = false
        change("")
        keyboard?.hide()
        scope.launch { withFrameNanos { }; runCatching { icon.requestFocus() } }
    }
    BackHandler(expanded, ::close)
    LaunchedEffect(imeVisible, expanded) {
        if (expanded && imeVisible) imeSeen = true
        else if (expanded && imeSeen) close()
    }
    LaunchedEffect(expanded) {
        if (expanded) {
            withFrameNanos { }
            if (runCatching { field.requestFocus() }.getOrDefault(false)) keyboard?.show()
        }
    }
    val width by animateDpAsState(if (expanded) (if (compact) 160.dp else 240.dp) else 44.dp,
        tween(180), label = "collectionSearchWidth")
    Row(Modifier.width(width).height(44.dp)) {
        if (expanded) Surface(Modifier.fillMaxSize(), shape = RoundedCornerShape(10.dp),
            color = Color(0xFF242831), border = BorderStroke(1.dp, Color(0xFF454B58))) {
            Row(Modifier.fillMaxSize().padding(start = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                BasicTextField(query, change, modifier = Modifier.weight(1f).focusRequester(field),
                    singleLine = true, textStyle = MaterialTheme.typography.bodySmall.copy(color = Color.White),
                    cursorBrush = SolidColor(Color.White),
                    decorationBox = { inner -> Box {
                        if (query.isEmpty()) Text("Search titles", color = Color.LightGray,
                            style = MaterialTheme.typography.bodySmall, maxLines = 1)
                        inner()
                    } },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { keyboard?.hide() }))
                IconButton(onClick = ::close, modifier = Modifier.size(40.dp)) {
                    Icon(Icons.Default.Close, "Close search", Modifier.size(18.dp))
                }
            }
        }
        else DiscoveryAction("Search titles", Icons.Default.Search, iconOnly = true,
            modifier = Modifier.focusRequester(icon)) { onActivate(); expanded = true }
    }
}
