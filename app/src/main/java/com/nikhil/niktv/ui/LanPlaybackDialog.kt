package com.nikhil.niktv.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nikhil.niktv.data.LanDevice

internal val LocalOpenLanPlayback = staticCompositionLocalOf<() -> Unit> { {} }
internal val LocalLanPlaybackTarget = staticCompositionLocalOf<String?> { null }
internal val LocalRequestLanPlayback = staticCompositionLocalOf<((com.nikhil.niktv.model.PlayingMedia, Long, () -> Unit) -> Unit)?> { null }
internal val LocalLanPlaybackController = staticCompositionLocalOf<com.nikhil.niktv.data.LanPlaybackController?> { null }
private data class PendingLanVideo(val media: com.nikhil.niktv.model.PlayingMedia, val at: Long, val accepted: () -> Unit)

@Composable
internal fun LanPlaybackHost(vm: NikTvViewModel, content: @Composable () -> Unit) {
    val app by vm.state.collectAsStateWithLifecycle()
    val portal = app.session?.profile?.let { com.nikhil.niktv.data.remotePortalIdentity(it.portalType.name, it.portalUrl) }
    LanPlaybackHost(vm.lanPlayback, portalIdentity = portal, content = content)
}

@Composable
internal fun LanPlaybackHost(controller: com.nikhil.niktv.data.LanPlaybackController, allowReceiving: Boolean = true,
    portalIdentity: String? = null, content: @Composable () -> Unit) {
    val state by controller.state.collectAsStateWithLifecycle()
    var open by rememberSaveable { mutableStateOf(false) }
    var pending by remember { mutableStateOf<PendingLanVideo?>(null) }
    val owner = LocalLifecycleOwner.current
    val context = LocalContext.current
    var notificationsAsked by rememberSaveable { mutableStateOf(false) }
    val notificationPermission = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { }
    DisposableEffect(controller, owner) {
        val observer = LifecycleEventObserver { _, event -> when (event) {
            Lifecycle.Event.ON_START -> controller.foreground(owner, true)
            Lifecycle.Event.ON_STOP -> controller.foreground(owner, false)
            else -> Unit
        } }
        owner.lifecycle.addObserver(observer)
        controller.foreground(owner, owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
        onDispose { owner.lifecycle.removeObserver(observer); controller.foreground(owner, false) }
    }
    LaunchedEffect(open, state.receiving) {
        if (open && !state.receiving) controller.discover() else controller.stopDiscovery()
    }
    LaunchedEffect(state.incomingPlayback) { if (state.incomingPlayback > 0) open = false }
    LaunchedEffect(state.message) {
        if (!open && state.message.startsWith("Playing "))
            android.widget.Toast.makeText(context, state.message, android.widget.Toast.LENGTH_SHORT).show()
    }
    LaunchedEffect(pending, state.target) {
        val selected = pending
        if (selected != null && state.target != null) {
            try { controller.playVideo(selected.media, selected.at, portalIdentity.takeUnless { selected.media.externalPlayback }); selected.accepted() }
            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (failure: Exception) { controller.reportFailure(if (failure is SecurityException) "Open the video again from your file manager to grant access." else "Could not send this video. Check the receiver and try again.") }
            finally { pending = null }
        }
    }
    CompositionLocalProvider(LocalOpenLanPlayback provides { open = true }, LocalLanPlaybackTarget provides state.target?.name,
        LocalLanPlaybackController provides controller,
        LocalRequestLanPlayback provides { media, at, accepted ->
            if (!notificationsAsked && android.os.Build.VERSION.SDK_INT >= 33 &&
                context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                notificationsAsked = true; notificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            }
            pending = PendingLanVideo(media, at, accepted); open = true
        }) {
        content()
    }
    if (open) MaterialTheme(colorScheme = NikColors) {
        LanPlaybackDialog(state, { open = false; if (state.target == null) pending = null }, controller::setReceiving, controller::disconnect,
            { device, pin -> controller.pair(device, pin) }, { controller.control(it) }, allowReceiving, pending?.media?.media?.title,
            controller::refreshDevices, controller::removePairedDevice)
    }
}

@Composable
internal fun LanPlaybackDialog(state: com.nikhil.niktv.data.LanPlaybackState, close: () -> Unit,
    receive: (Boolean) -> Unit, disconnect: () -> Unit, pair: (LanDevice, String) -> Unit,
    control: (String) -> Unit, allowReceiving: Boolean = true, pendingTitle: String? = null, refresh: () -> Unit = {},
    removePaired: (String) -> Unit = {}) {
    var selected by remember { mutableStateOf<LanDevice?>(null) }
    var host by rememberSaveable { mutableStateOf("") }
    var port by rememberSaveable { mutableStateOf("") }
    var pin by rememberSaveable { mutableStateOf("") }
    var manual by rememberSaveable { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current
    val manualFocus = remember { FocusRequester() }
    val hostFocus = remember { FocusRequester() }
    val portFocus = remember { FocusRequester() }
    val pinFocus = remember { FocusRequester() }
    val connectFocus = remember { FocusRequester() }
    val receiveFocus = remember { FocusRequester() }
    val doneFocus = remember { FocusRequester() }
    val footerFocus = remember { FocusRequester() }
    val remoteFocus = remember { FocusRequester() }
    val inputMode = LocalInputModeManager.current
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    // Touch must enter editors directly. Keep the opening policy stable so a
    // later hardware-key event cannot redirect an active editor back to Done.
    val focusActionsOnOpen = remember {
        context.isTvLikeDevice(configuration) || inputMode.inputMode == InputMode.Keyboard
    }
    val currentDevice = com.nikhil.niktv.data.currentLanDevice(state.devices, selected)
    val canConnect = !state.busy && pin.length == 6 &&
        (if (manual) host.isNotBlank() && port.toIntOrNull() in 1..65535 else currentDevice != null)
    fun connect() {
        keyboard?.hide()
        (if (manual) LanDevice("NikTV $host", host, port.toIntOrNull() ?: 0) else currentDevice)?.let { pair(it, pin) }
    }
    fun fieldNavigation(up: FocusRequester, down: FocusRequester, left: FocusRequester? = null, right: FocusRequester? = null) = Modifier.onPreviewKeyEvent { event ->
        if (event.type == KeyEventType.KeyDown && (event.key == Key.DirectionLeft && left != null || event.key == Key.DirectionRight && right != null)) {
            keyboard?.hide(); (if (event.key == Key.DirectionLeft) left else right)?.requestFocus(); true
        } else
        if (event.type == KeyEventType.KeyDown && event.key in listOf(Key.DirectionUp, Key.DirectionDown)) {
            keyboard?.hide(); (if (event.key == Key.DirectionUp) up else down).requestFocus(); true
        } else false
    }
    val muted = Color(0xFFADB4C2)
    @Composable fun receiverCode(modifier: Modifier) {
        LanPlaybackSection("Ready to receive", Icons.Default.Tv, modifier) {
            Text("Pairing code", style = MaterialTheme.typography.labelMedium, color = muted)
            Text(state.pin.chunked(3).joinToString(" "), fontSize = 32.sp, lineHeight = 38.sp,
                fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold)
            val seconds = state.pinSecondsRemaining
            Text("Refreshes automatically in ${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}",
                style = MaterialTheme.typography.bodySmall, color = muted)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(Icons.Default.Wifi, null, Modifier.size(16.dp), tint = muted)
                Text("${state.address}:${state.port}", style = MaterialTheme.typography.bodySmall)
            }
            Text("Keep NikTV open here.", style = MaterialTheme.typography.bodySmall, color = muted)
        }
    }
    @Composable fun pairedDevices(modifier: Modifier) {
        LanPlaybackSection("Paired devices (${state.pairedDevices.size})", Icons.Default.Devices, modifier) {
            if (state.pairedDevices.isEmpty()) Text("No devices paired yet.", style = MaterialTheme.typography.bodySmall, color = muted)
            Column(Modifier.fillMaxWidth().heightIn(max = 160.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                state.pairedDevices.forEach { device ->
                    Surface(shape = RoundedCornerShape(12.dp), color = Color(0xFF191F29), border = BorderStroke(1.dp, Color(0xFF2B303A))) {
                        Row(Modifier.fillMaxWidth().padding(start = 10.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.PhoneAndroid, null, Modifier.size(20.dp), tint = muted)
                            Column(Modifier.weight(1f).padding(horizontal = 8.dp, vertical = 6.dp)) {
                                Text(device.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                                Text(if (device.ownsPlayback) "Controls playback" else "Paired · ready", style = MaterialTheme.typography.bodySmall,
                                    color = if (device.ownsPlayback) NikColors.primary else muted)
                            }
                            NikTvSecondaryActionButton({ removePaired(device.id) }, enabled = !state.busy,
                                contentPadding = PaddingValues(10.dp)) {
                                Icon(Icons.Default.LinkOff, "Disconnect ${device.name}", Modifier.size(20.dp))
                            }
                        }
                    }
                }
            }
            Text("One device controls playback at a time.",
                style = MaterialTheme.typography.bodySmall, color = muted)
        }
    }
    @Composable fun devicePicker(modifier: Modifier) {
        LanPlaybackSection(if (manual) "Receiver address" else "Nearby devices", Icons.Default.Devices, modifier) {
            if (!manual) {
                Column(Modifier.fillMaxWidth().heightIn(max = 160.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    state.devices.forEach { device ->
                        NikTvSecondaryActionButton({ selected = device; manual = false }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Default.Tv, null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp))
                            Text(device.name, Modifier.weight(1f), maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                            if (currentDevice?.name == device.name) Icon(Icons.Default.Check, "Selected", Modifier.size(20.dp), tint = NikColors.primary)
                        }
                    }
                }
                if (state.devices.isEmpty()) Text("Looking for NikTV devices…", style = MaterialTheme.typography.bodySmall, color = muted)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                NikTvSecondaryActionButton({ manual = !manual; selected = null }, modifier = Modifier.weight(1f).focusRequester(manualFocus)) {
                    Icon(if (manual) Icons.Default.Tv else Icons.Default.Link, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp))
                    Text(if (manual) "Nearby devices" else "Connect by address", style = MaterialTheme.typography.labelMedium,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (!manual) NikTvSecondaryActionButton({ selected = null; refresh() }, enabled = !state.busy, contentPadding = PaddingValues(12.dp)) {
                    Icon(Icons.Default.Refresh, "Refresh devices", Modifier.size(20.dp))
                }
            }
            if (allowReceiving) NikTvTextActionButton({ receive(true) }, enabled = !state.busy,
                modifier = Modifier.focusRequester(receiveFocus).fillMaxWidth()) {
                Icon(Icons.Default.Tv, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp))
                Text("Receive on this device", style = MaterialTheme.typography.labelMedium)
            }
        }
    }
    @Composable fun pairingFields(modifier: Modifier) {
        LanPlaybackSection("Pair with receiver", Icons.Default.Link, modifier) {
            if (manual) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(host, { host = it.trim() }, label = { Text("IP address") }, singleLine = true,
                    modifier = Modifier.weight(1f).focusRequester(hostFocus).then(fieldNavigation(manualFocus, pinFocus, right = portFocus)),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next), keyboardActions = KeyboardActions(onNext = { portFocus.requestFocus() }))
                OutlinedTextField(port, { port = it.filter(Char::isDigit).take(5) }, label = { Text("Port") }, singleLine = true,
                    modifier = Modifier.width(88.dp).focusRequester(portFocus).then(fieldNavigation(manualFocus, pinFocus, left = hostFocus)),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
                    keyboardActions = KeyboardActions(onNext = { pinFocus.requestFocus() }))
            }
            if (currentDevice != null || manual) {
                OutlinedTextField(pin, { pin = it.filter(Char::isDigit).take(6) }, label = { Text("6-digit pairing code") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth().focusRequester(pinFocus).then(fieldNavigation(if (manual) hostFocus else manualFocus, if (canConnect) connectFocus else doneFocus)),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { keyboard?.hide() }))
            } else Text("Select a device, then enter the code shown on its screen.", style = MaterialTheme.typography.bodySmall, color = muted)
        }
    }
    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).imePadding().padding(12.dp), contentAlignment = Alignment.Center) {
            val wide = maxWidth >= 600.dp
            Surface(Modifier.widthIn(max = 920.dp).fillMaxWidth().heightIn(max = maxHeight)
                .semantics { paneTitle = "Play on NikTV" }
                .initialFocusEntry(doneFocus, enabled = focusActionsOnOpen),
                shape = RoundedCornerShape(18.dp), color = NikColors.background, contentColor = Color.White,
                border = BorderStroke(1.dp, Color.White.copy(alpha = .14f))) {
                Column(Modifier.padding(if (wide) 16.dp else 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Surface(shape = RoundedCornerShape(10.dp), color = NikColors.primary.copy(alpha = .16f)) {
                            Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                                Icon(if (state.receiving) Icons.Default.Tv else Icons.Default.Cast, null, Modifier.size(24.dp), tint = NikColors.primary)
                            }
                        }
                        Column(Modifier.weight(1f)) {
                            Text(if (state.receiving) "Receive on NikTV" else "Play on NikTV", style = MaterialTheme.typography.titleMedium)
                            Text("Connect over the same Wi-Fi", style = MaterialTheme.typography.bodySmall, color = muted)
                        }
                    }
                    Column(Modifier.weight(1f, fill = false).fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        (pendingTitle ?: state.remoteVideo)?.let { Text(it, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) }
                        if (state.receiving) {
                            if (wide) Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                receiverCode(Modifier.weight(1f)); pairedDevices(Modifier.weight(1f))
                            } else { receiverCode(Modifier.fillMaxWidth()); pairedDevices(Modifier.fillMaxWidth()) }
                        } else if (state.target != null) {
                            LanPlaybackSection("Connected to ${state.target.name}", Icons.Default.CastConnected, Modifier.fillMaxWidth()) {
                                Text("Choose a movie or send a video from the player. You can keep using your phone.", style = MaterialTheme.typography.bodySmall, color = muted)
                                Row(Modifier.fillMaxWidth().focusProperties { down = footerFocus },
                                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)) {
                                    LanRemoteAction("Pause", Icons.Default.Pause, !state.busy, Modifier.focusRequester(remoteFocus)) { control("pause") }
                                    LanRemoteAction("Resume", Icons.Default.PlayArrow, !state.busy) { control("resume") }
                                    LanRemoteAction("Stop", Icons.Default.Stop, !state.busy) { control("stop") }
                                    LanRemoteAction("Playback status", Icons.Default.Refresh, !state.busy) { control("status") }
                                }
                            }
                        } else {
                            Text("On your TV or tablet, choose Receive on this device.", style = MaterialTheme.typography.bodySmall, color = muted)
                            if (wide) Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                devicePicker(Modifier.weight(1f)); pairingFields(Modifier.weight(1f))
                            } else { devicePicker(Modifier.fillMaxWidth()); pairingFields(Modifier.fillMaxWidth()) }
                        }
                        if (state.message.isNotBlank()) Text(state.message, style = MaterialTheme.typography.bodySmall, color = muted)
                        if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                    HorizontalDivider(color = Color.White.copy(alpha = .1f))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically) {
                        val footerRoute = Modifier.focusRequester(footerFocus).focusProperties { right = doneFocus }
                        if (state.receiving) NikTvSecondaryActionButton({ receive(false) }, enabled = !state.busy, modifier = footerRoute) { Text("Stop receiving") }
                        else if (state.target != null) NikTvSecondaryActionButton(disconnect, enabled = !state.busy,
                            modifier = footerRoute.focusProperties { up = remoteFocus }) { Text("Disconnect") }
                        else NikTvPrimaryActionButton(::connect, enabled = canConnect, modifier = Modifier.focusRequester(connectFocus)
                            .focusProperties { right = doneFocus; up = if (currentDevice != null || manual) pinFocus else manualFocus }) { Text("Connect") }
                        NikTvSecondaryActionButton(close, modifier = Modifier.focusRequester(doneFocus).focusProperties {
                            if (state.receiving || state.target != null) left = footerFocus else if (canConnect) left = connectFocus
                            if (state.target != null) up = remoteFocus
                        }) { Text("Done") }
                    }
                }
            }
        }
    }
}

@Composable
private fun LanPlaybackSection(title: String, icon: ImageVector, modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit) {
    com.nikhil.niktv.discovery.DiscoveryGlassPanel(modifier, artwork = null,
        backdropBounds = androidx.compose.ui.geometry.Rect.Zero, baseColor = Color(0xFF171C26), tint = Color.Transparent) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(icon, null, Modifier.size(18.dp), tint = Color(0xFFADB4C2))
                Text(title, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            content()
        }
    }
}

@Composable
private fun LanRemoteAction(label: String, icon: ImageVector, enabled: Boolean, modifier: Modifier = Modifier,
    action: () -> Unit) {
    NikTvSecondaryActionButton(action, modifier.size(48.dp).semantics { contentDescription = label }, enabled,
        contentPadding = PaddingValues(10.dp)) {
        Icon(icon, null, Modifier.size(22.dp))
    }
}

@Composable
internal fun RemotePlaybackStandby(onBack: () -> Unit, mediaId: String? = null): Boolean {
    val controller = LocalLanPlaybackController.current ?: return false
    val state by controller.state.collectAsStateWithLifecycle()
    if (mediaId != null && state.remoteMediaId != mediaId) return false
    val title = state.remoteVideo ?: return false
    val open = LocalOpenLanPlayback.current
    val context = LocalContext.current
    DisposableEffect(context) {
        (context.findHostActivity() as? PlayerPictureInPictureHost)?.setPlayerActiveForPip(false)
        onDispose { }
    }
    Surface(Modifier.fillMaxSize(), color = NikColors.background) {
        Box(Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp), contentAlignment = Alignment.Center) {
            LanPlaybackSection("Playing on ${state.target?.name ?: "your receiver"}", Icons.Default.CastConnected,
                Modifier.widthIn(max = 620.dp).fillMaxWidth()) {
                Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
                Text("You can use other apps. Keep this phone on Wi-Fi.", style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFFADB4C2))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NikTvPrimaryActionButton(onClick = open) {
                        Icon(Icons.Default.Cast, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Remote controls")
                    }
                    NikTvSecondaryActionButton(onClick = onBack) { Text("Back") }
                }
            }
        }
    }
    return true
}
