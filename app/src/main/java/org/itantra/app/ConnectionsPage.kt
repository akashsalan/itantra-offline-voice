package org.itantra.app

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.itantra.app.core.ConnectionChoice
import org.itantra.app.transport.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun ConnectionsPage(app: AppRuntime, wifiAction: (() -> Unit) -> Unit,
    bluetoothAction: (String) -> Unit, onTalk: () -> Unit) {
    val mode by app.mode.collectAsStateWithLifecycle()
    val settings by app.settings.collectAsStateWithLifecycle()
    val room by app.lan.state.collectAsStateWithLifecycle()
    val session by app.session.collectAsStateWithLifecycle()
    val link by app.link.collectAsStateWithLifecycle()
    val busy by app.lanBusy.collectAsStateWithLifecycle()
    val talk by app.talk.collectAsStateWithLifecycle()
    val group = mode == RadioMode.WIFI_DIRECT_GROUP || settings.connectionGroup
    LaunchedEffect(Unit) { app.connectionGuidance() }
    var choosingMethod by remember { mutableStateOf(false) }
    val canChange = ConnectionChoice.canChange(session.ready, room.active,
        link is LinkState.Connecting || link is LinkState.Connected, busy || talk.busy)
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Connections", style = MaterialTheme.typography.titleLarge)
            Hint("Nearby communication · no internet required")
            Text("1 · Conversation", style = MaterialTheme.typography.labelLarge)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                SegmentedButton(!group, { app.chooseConnection(false, ConnectionChoice.restore(false, mode.name).method) },
                    SegmentedButtonDefaults.itemShape(0, 2), enabled = canChange) { Text("One-to-one") }
                SegmentedButton(group, { app.chooseConnection(true, ConnectionChoice.restore(true, mode.name).method) },
                    SegmentedButtonDefaults.itemShape(1, 2), enabled = canChange) { Text("Group") }
            }
            OutlinedButton({ choosingMethod = true }, Modifier.fillMaxWidth().heightIn(min = 64.dp), enabled = canChange,
                shape = AppDesign.Control) {
                Icon(connectionIcon(mode), null, Modifier.size(24.dp))
                Column(Modifier.weight(1f).padding(horizontal = 12.dp), horizontalAlignment = Alignment.Start) {
                    Text("2 · Connection method", style = MaterialTheme.typography.labelMedium)
                    Text(mode.label, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                }
                Icon(Icons.Default.ArrowDropDown, null)
            }
            if (!canChange) Hint("Disconnect or finish the current action to change this selection.")
        }
        HorizontalDivider()
        Box(Modifier.weight(1f)) {
            when {
                mode == RadioMode.WIFI_DIRECT_GROUP -> DirectGroupPage(app, wifiAction, onTalk)
                mode.isLan -> LanDevicesPage(app, group, onTalk)
                else -> DirectPhonePage(app, wifiAction, bluetoothAction, onTalk)
            }
        }
    }
    if (choosingMethod) ModalBottomSheet(onDismissRequest = { choosingMethod = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).navigationBarsPadding()) {
            Text("Connection method", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(12.dp))
            ConnectionChoice.methods(group).forEach { option ->
                val unsupported = (option == RadioMode.WIFI_DIRECT && !app.wifi.supported) ||
                    (option == RadioMode.WIFI_DIRECT_GROUP && !app.directGroups.supported)
                ListItem(headlineContent = { Text(option.label, fontWeight = FontWeight.SemiBold) },
                    supportingContent = { Text(methodHelp(option) + if (unsupported) "\nUnavailable on this phone" else if (mode == option) "\nSelected" else "") },
                    leadingContent = { Icon(connectionIcon(option), null, tint = MaterialTheme.colorScheme.primary) },
                    trailingContent = { RadioButton(mode == option, null) },
                    modifier = Modifier.selectable(mode == option, role = Role.RadioButton) {
                        choosingMethod = false; app.chooseConnection(group, option)
                    })
            }
            if (group) Hint("Bluetooth Classic currently supports one-to-one only. Emergency BLE relay is a separate experimental feature.")
            Spacer(Modifier.height(24.dp))
        }
    }
}

private fun connectionIcon(mode: RadioMode) = when (mode) {
    RadioMode.BLUETOOTH -> AppIcons.Bluetooth
    RadioMode.HOTSPOT -> AppIcons.Hotspot
    RadioMode.WIFI_DIRECT, RadioMode.WIFI_DIRECT_GROUP -> AppIcons.Radio
    RadioMode.SAME_WIFI -> AppIcons.Connections
}

internal fun methodHelp(mode: RadioMode) = when (mode) {
    RadioMode.WIFI_DIRECT -> "Connect directly to one phone. No router or hotspot."
    RadioMode.WIFI_DIRECT_GROUP -> "Create a group directly between phones. No router or hotspot."
    RadioMode.BLUETOOTH -> "Connect to one phone using Bluetooth. No Wi-Fi needed."
    RadioMode.SAME_WIFI -> "Everyone joins the same local Wi-Fi network. Internet is unnecessary."
    RadioMode.HOTSPOT -> "One phone provides a hotspot; others join it. Mobile data can stay off."
}

@Composable private fun DirectPhonePage(app: AppRuntime, wifiAction: (() -> Unit) -> Unit,
    bluetoothAction: (String) -> Unit, onTalk: () -> Unit) {
    val settings by app.settings.collectAsStateWithLifecycle()
    val mode by app.mode.collectAsStateWithLifecycle()
    val link by app.link.collectAsStateWithLifecycle()
    val session by app.session.collectAsStateWithLifecycle()
    val peers by app.peers.collectAsStateWithLifecycle()
    val ble by app.ble.state.collectAsStateWithLifecycle()
    var advanced by rememberSaveable { mutableStateOf(false) }
    val idle = link !is LinkState.Connected && link !is LinkState.Connecting
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), contentPadding = PaddingValues(vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text("3 · Connect a nearby phone", style = MaterialTheme.typography.titleMedium)
            Hint("This phone: " + settings.name)
            Hint(methodHelp(mode))
        }
        if (link != LinkState.Disconnected || session.ready) item { ConnectionCard(session.ready, session.peerName, link, mode.label) {} }
        if (idle) item {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // One action only. Discovery already advertises this phone, so a
                // separate "make available" button did the same thing twice.
                Button({ if (mode == RadioMode.BLUETOOTH) bluetoothAction("discover") else wifiAction(app::discover) },
                    Modifier.fillMaxWidth().heightIn(min = 56.dp), shape = AppDesign.Control,
                    enabled = mode != RadioMode.WIFI_DIRECT || app.wifi.supported) {
                    Icon(Icons.Default.Search, null); Spacer(Modifier.width(8.dp)); Text("Find nearby devices")
                }
                if (mode == RadioMode.BLUETOOTH) {
                    OutlinedButton({ bluetoothAction("visible") },
                        Modifier.fillMaxWidth().heightIn(min = 56.dp), shape = AppDesign.Control) {
                        Text("Let others find this phone")
                    }
                    Hint("Bluetooth needs a short visibility window before an unpaired phone can see you.")
                } else {
                    Hint("This phone is discoverable while Connections is open. Open it on both, connect from one, then compare the codes.")
                }
            }
        }
        if (mode == RadioMode.WIFI_DIRECT && !app.wifi.supported) item {
            Hint("This phone does not support Wi-Fi Direct. Choose Bluetooth Classic or a shared local network.")
        }
        if (session.code.isNotBlank()) item {
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Compare the code on both phones", fontWeight = FontWeight.SemiBold)
                    Text(session.code, style = MaterialTheme.typography.headlineMedium)
                    Text(session.peerName)
                    Button(app::confirmPairing, enabled = !session.localConfirmed) {
                        Text(if (session.localConfirmed) "You confirmed" else "Codes match · confirm")
                    }
                    Hint(if (session.ready) "Both confirmed. Ready to talk." else "Waiting for both people to confirm.")
                    if (session.ready) Button(onTalk, Modifier.fillMaxWidth()) { Text("Open Talk") }
                }
            }
        }
        if (peers.isEmpty() && session.code.isBlank()) item {
            if (link is LinkState.Discovering) SearchingCard(
                "Looking for nearby phones…",
                "Keep both phones unlocked with Connections open. This takes a few seconds.",
                active = true, reducedMotion = settings.reducedMotion
            ) else EmptyCard("No phone selected",
                "Ask the other person to open Connections. You can still record drafts or install a language.")
        }
        items(peers, key = { it.id }) { peer ->
            ListItem(headlineContent = { Text(peer.name) }, supportingContent = { Text("Nearby iTantra") },
                trailingContent = { TextButton({ app.connect(peer) }, enabled = idle) { Text("Connect") } })
        }
        session.error?.let { item { Hint(it, MaterialTheme.colorScheme.tertiary) } }
        if (link != LinkState.Disconnected) item { DisconnectButton(app) }
        item {
            TextButton({ advanced = !advanced }) { Text(if (advanced) "Hide advanced connection help" else "Advanced connection help") }
            if (advanced) Column(Modifier.animateContentSize(tween(if (settings.reducedMotion) 0 else 180)),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ConnectionRecovery(mode)
                Hint("One peer per session. This matching code is a session cross-check; the legacy connection uses link-layer security.")
                Text("BLE presence discovery", fontWeight = FontWeight.SemiBold)
                Hint("Presence only—not a message link or mesh. Use Bluetooth Classic for this one-to-one connection.")
                OutlinedButton({ bluetoothAction("ble") }, enabled = app.ble.supported && idle) { Text("Scan nearby presence") }
                if (ble.scanning || ble.advertising) TextButton(app::stopBle) { Text("Stop scan") }
                Hint(ble.detail)
                ble.peers.forEach { Text(it.name) }
            }
        }
    }
}

@Composable private fun DirectGroupPage(app: AppRuntime, wifiAction: (() -> Unit) -> Unit, onTalk: () -> Unit) {
    val radio by app.directGroups.state.collectAsStateWithLifecycle()
    val room by app.lan.state.collectAsStateWithLifecycle()
    val busy by app.lanBusy.collectAsStateWithLifecycle()
    val saved by app.lan.savedName.collectAsStateWithLifecycle()
    var creating by remember { mutableStateOf(false) }
    var name by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    DisposableEffect(app) { onDispose { app.stopDirectGroupDiscovery() } }
    if (room.active) { LanDevicesPage(app, true, onTalk); return }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), contentPadding = PaddingValues(vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text("3 · Start a direct group", style = MaterialTheme.typography.titleMedium)
            Hint("No router, hotspot or internet required. Keep Wi-Fi on and the creator's phone nearby.")
        }
        item {
            Button({ creating = true }, Modifier.fillMaxWidth().heightIn(min = 56.dp), enabled = !busy && !radio.formed && app.directGroups.supported) { Text("Create group") }
            OutlinedButton({ wifiAction(app::discoverDirectGroups) }, Modifier.fillMaxWidth().heightIn(min = 56.dp),
                enabled = !busy && !radio.formed && app.directGroups.supported) { Text(if (radio.discovering) "Refresh nearby groups" else "Find group") }
            if (saved.isNotBlank()) TextButton({ wifiAction { app.createLan("", "", true, resume = true) } },
                enabled = !busy && !radio.formed && app.directGroups.supported) { Text("Resume " + saved) }
        }
        item {
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            Hint(radio.detail)
            if (!app.directGroups.supported) Hint("Direct groups are unavailable on this phone. A phone hotspot is the alternative; mobile data is not required.")
        }
        if (radio.peers.isEmpty()) item {
            EmptyCard("No direct groups found yet", "Ask one person to choose Create group. Everyone else chooses Find group and joins that phone.")
        }
        items(radio.peers, key = { it.address }) { peer ->
            ListItem(headlineContent = { Text(peer.groupName) }, supportingContent = { Text(peer.phone) },
                trailingContent = { TextButton({ wifiAction { app.joinDirectGroup(peer) } }, enabled = !busy && !radio.formed) { Text("Join") } })
        }
        if (radio.active) item { DisconnectButton(app) }
        item {
            Hint("Group capacity and discovery vary by phone. Multi-phone verification is still required; this is not BLE mesh.")
            ConnectionRecovery(RadioMode.WIFI_DIRECT_GROUP)
        }
    }
    if (creating) AlertDialog(onDismissRequest = { creating = false; password = "" }, title = { Text("Create direct group") },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Others will find this group in iTantra. No Wi-Fi password or hotspot setup is needed.")
            OutlinedTextField(name, { name = it.take(48) }, Modifier.fillMaxWidth(), label = { Text("Group name") }, singleLine = true)
            OutlinedTextField(password, { password = it.take(64) }, Modifier.fillMaxWidth(), label = { Text("Group password") },
                supportingText = { Text("8–64 characters. Share with intended members.") }, singleLine = true, visualTransformation = PasswordVisualTransformation())
        } }, confirmButton = { TextButton({
            val chosenName = name; val chosenPassword = password
            creating = false; password = ""
            wifiAction { app.createLan(chosenName, chosenPassword, true) }
        }, enabled = name.isNotBlank() && password.length >= 8) { Text("Create group") } },
        dismissButton = { TextButton({ creating = false; password = "" }) { Text("Cancel") } })
}

@Composable internal fun DisconnectButton(app: AppRuntime) {
    val room by app.lan.state.collectAsStateWithLifecycle()
    var confirm by remember { mutableStateOf(false) }
    val title = if (room.active && room.group) { if (room.hosting) "End group" else "Leave group" } else "Disconnect"
    OutlinedButton({ confirm = true }, Modifier.heightIn(min = 48.dp)) { Text(title) }
    if (confirm) AlertDialog(onDismissRequest = { confirm = false }, title = { Text(title + "?") },
        text = { Text(if (room.hosting && room.group) "Members will lose this group connection. Everyone's local message history is kept."
            else "Your local message history and imported language models are kept.") },
        confirmButton = { TextButton({ confirm = false; app.disconnect() }) { Text(title) } },
        dismissButton = { TextButton({ confirm = false }) { Text("Keep connection") } })
}

@Composable internal fun ConnectionRecovery(mode: RadioMode) {
    val context = LocalContext.current
    fun open(action: String, data: Uri? = null) { runCatching { context.startActivity(Intent(action, data)) } }
    Column {
        TextButton({ open(if (mode == RadioMode.BLUETOOTH) Settings.ACTION_BLUETOOTH_SETTINGS else Settings.ACTION_WIFI_SETTINGS) }) {
            Text(if (mode == RadioMode.BLUETOOTH) "Bluetooth settings" else "Wi-Fi settings")
        }
        TextButton({ open(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + context.packageName)) }) { Text("App permissions") }
        if (Build.VERSION.SDK_INT <= 32) TextButton({ open(Settings.ACTION_LOCATION_SOURCE_SETTINGS) }) { Text("Location mode for discovery") }
    }
}
