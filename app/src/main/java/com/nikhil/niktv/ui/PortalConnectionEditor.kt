package com.nikhil.niktv.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nikhil.niktv.data.cast4kLegacyDeviceIdentity
import com.nikhil.niktv.model.*

@Composable
internal fun PortalConnectionEditor(saved: PortalProfile?, loading: Boolean,
    connect: (PortalProfile) -> Unit, cancelEditor: () -> Unit, importBackup: (android.net.Uri) -> Unit) {
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(importBackup) }
    val context = LocalContext.current
    val generatedIdentity = remember(context) { cast4kLegacyDeviceIdentity(context) }
    val initial = saved ?: PortalProfile("", "", "")
    var name by remember(saved) { mutableStateOf(initial.name) }
    var url by remember(saved) { mutableStateOf(initial.portalUrl) }
    var mac by remember(saved) { mutableStateOf(initial.macAddress) }
    var serial by remember(saved) { mutableStateOf(initial.serialNumber) }
    var portalType by remember(saved) { mutableStateOf(saved?.portalType ?: PortalType.STALKER) }
    var username by remember(saved) { mutableStateOf(initial.username) }
    var password by remember(saved) { mutableStateOf(initial.password) }
    var advanced by remember(saved) { mutableStateOf(initial.serialNumber.isNotBlank()) }
    fun useDefaults(type: PortalType) {
        portalType = type
        if (saved == null) {
            name = ""; url = ""; mac = ""; serial = ""; username = ""; password = ""
            advanced = false
        }
    }
    val keyboard = LocalSoftwareKeyboardController.current
    BackHandler(onBack = cancelEditor)
    Box(Modifier.fillMaxSize().background(Color(0xFF090909)).statusBarsPadding().imePadding().padding(16.dp), contentAlignment = Alignment.Center) {
        Card(Modifier.widthIn(max = 520.dp).fillMaxHeight(), shape = RoundedCornerShape(12.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFF111827))) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = cancelEditor) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back to settings") }
                    Column { Text(if (saved == null) "Configure portal" else "Edit portal", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold); Text("Portal credentials stay local to this device", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    SegmentedButton(portalType == PortalType.STALKER, { useDefaults(PortalType.STALKER) }, uniformSegmentShape(0, 2), modifier = Modifier.remoteFocusFrame(uniformSegmentShape(0, 2))) { Text("Stalker / MAG") }
                    SegmentedButton(portalType == PortalType.XTREAM, { useDefaults(PortalType.XTREAM) }, uniformSegmentShape(1, 2), modifier = Modifier.remoteFocusFrame(uniformSegmentShape(1, 2))) { Text("Xtream") }
                }
                SettingsTextEditRow("Portal name", name, { name = it }, "An optional label for this content connection.")
                SettingsTextEditRow("Portal URL", url, { url = it }, "The provider portal address.", placeholder = "https://provider.example")
                if (portalType == PortalType.XTREAM) {
                    SettingsTextEditRow("Username", username, { username = it }, "The username supplied by your provider.")
                    SettingsTextEditRow("Password", password, { password = it }, "The password supplied by your provider.", password = true)
                } else {
                    SettingsTextEditRow("MAC address", mac, { mac = it }, "The MAC address registered with your provider.", placeholder = "00:1A:79:XX:XX:XX")
                    NikTvTextActionButton(onClick = {
                        mac = generatedIdentity.macAddress
                        serial = generatedIdentity.serialNumber
                        advanced = true
                    }) {
                        Icon(Icons.Default.AutoFixHigh, null)
                        Spacer(Modifier.width(8.dp))
                        Text("Generate compatible device identity")
                    }
                    NikTvTextActionButton(onClick = { advanced = !advanced }) { Text(if (advanced) "Hide advanced identity" else "Advanced identity") }
                    if (advanced) SettingsTextEditRow("Portal serial number (optional)", serial, { serial = it }, "Use the serial registered for this MAC, or leave blank to generate one.")
                }
                val credentialsReady = if (portalType == PortalType.XTREAM) username.isNotBlank() && password.isNotBlank() else mac.isNotBlank()
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    NikTvPrimaryActionButton(onClick = { keyboard?.hide(); connect(PortalProfile(name.trim().ifBlank { if (portalType == PortalType.STALKER) "Stalker" else "Xtream" }, url.trim(), mac.trim(), serial.trim(), portalType, username.trim(), password)) }, enabled = !loading && url.isNotBlank() && credentialsReady, modifier = Modifier.weight(1f)) { Text(if (saved == null) "Connect portal" else "Save portal") }
                    if (saved == null) NikTvSecondaryActionButton(onClick = { keyboard?.hide(); cancelEditor() }, modifier = Modifier.weight(1f)) { Text("Cancel") }
                }
                NikTvSecondaryActionButton(
                    onClick = { importLauncher.launch(arrayOf("application/json", "text/json", "text/plain")) },
                    modifier = Modifier.fillMaxWidth().remoteFocusFrame(RoundedCornerShape(10.dp))
                ) { Icon(Icons.Default.FileDownload, null); Spacer(Modifier.width(8.dp)); Text("Import NikTV backup") }
                Text("Only connect to services you are authorized to access.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.navigationBarsPadding())
            }
        }
    }
}
