package org.itantra.app

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.itantra.app.lan.*
import org.itantra.app.transport.RadioMode
import org.itantra.app.transport.LinkState


@Composable internal fun LanConnectionCard(app: AppRuntime, onDevices: () -> Unit) {
    val room by app.lan.state.collectAsStateWithLifecycle()
    val mode by app.mode.collectAsStateWithLifecycle()
    var members by remember { mutableStateOf(false) }
    Card(onClick = if (room.connected && room.group) ({ members = true }) else onDevices, modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = if (room.connected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(if (room.active || room.roomId.isNotBlank()) "To " + room.title else "No local conversation", fontWeight = FontWeight.SemiBold)
            Hint(mode.label + " · " + room.status)
            if (room.connected && room.group) Hint("Tap to view members · Hindi messages play in Hindi, and likewise for each language")
        }
    }
    if (members) AlertDialog(onDismissRequest = { members = false }, title = { Text(room.name) },
        text = { Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            room.members.forEach { person ->
                Text(person.name + (if (person.id == room.selfId) " · you" else "") + (if (person.id == room.hostId) " · creator" else ""))
                Hint(if (person.online) "Connected" else "Offline")
            }
        } }, confirmButton = { TextButton({ members = false }) { Text("Done") } })
}

@Composable internal fun LanDevicesPage(app: AppRuntime, groups: Boolean, onTalk: () -> Unit) {
    val mode by app.mode.collectAsStateWithLifecycle()
    val room by app.lan.state.collectAsStateWithLifecycle()
    val network by app.localDiscovery.network.collectAsStateWithLifecycle()
    val nearby by app.localDiscovery.rooms.collectAsStateWithLifecycle()
    val detail by app.localDiscovery.detail.collectAsStateWithLifecycle()
    val saved by app.lan.savedName.collectAsStateWithLifecycle()
    val settings by app.settings.collectAsStateWithLifecycle()
    val busy by app.lanBusy.collectAsStateWithLifecycle()
    var creating by remember(groups, mode) { mutableStateOf(false) }
    var setupHelp by rememberSaveable { mutableStateOf(false) }
    var groupName by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var manual by rememberSaveable { mutableStateOf("") }
    var advanced by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    DisposableEffect(app, mode) { app.browseLan(true); onDispose { app.browseLan(false) } }
    // Be discoverable without a tap. One-to-one only: groups still need an
    // explicit name and password, and an active room is never disturbed.
    LaunchedEffect(app, groups, network.ready, room.active, busy) {
        if (!groups && network.ready && !room.active && !busy) app.autoHostDirect()
    }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), contentPadding = PaddingValues(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(if (mode == RadioMode.WIFI_DIRECT_GROUP) "Direct phone group" else if (network.ready) "Ready on your local network" else "Connect to a local network first", fontWeight = FontWeight.SemiBold)
                Hint(if (mode == RadioMode.WIFI_DIRECT_GROUP) "Phones are linked directly. Keep the creator's phone nearby; no router or hotspot is needed."
                    else if (mode == RadioMode.HOTSPOT) "One phone provides a hotspot; everyone else joins it."
                    else "Everyone must be connected to the same Wi-Fi network.")
                if (groups && mode == RadioMode.HOTSPOT) Hint("The hotspot owner or any reachable connected phone can create the group.")
                if (!network.ready) Hint(if (mode == RadioMode.WIFI_DIRECT_GROUP) "Waiting for Android to finish the direct connection. Rejoin if it does not recover."
                    else "Joining and creating unlock when Wi-Fi is connected or this phone's hotspot is enabled.")
                if (mode != RadioMode.WIFI_DIRECT_GROUP && (setupHelp || !network.ready)) Row {
                    TextButton({ runCatching { context.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS)) } }) { Text("Wi-Fi settings") }
                    TextButton({ runCatching { context.startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS)) } }) { Text("Hotspot settings") }
                }
                if (network.ready && mode != RadioMode.WIFI_DIRECT_GROUP) TextButton({ setupHelp = !setupHelp }) { Text(if (setupHelp) "Hide network settings" else "Network settings") }
            } }
        }
        if (room.active) {
            item {
                LanConnectionCard(app) {}
                if (room.hosting) Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(if (room.group) "You created ${room.name}" else "This phone is available for a direct connection", fontWeight = FontWeight.SemiBold)
                        Text("Creator's code: " + LanRules.code(room.selfId), fontWeight = FontWeight.Bold)
                        Hint(if (room.group) "Joining users compare this code before entering their group password." else "The other person checks this code, then you approve their request.")
                        if (room.group) SettingSwitch("Allow new joins", "Password is still required", room.accepting, app::acceptLanJoins)
                        Hint(if (mode == RadioMode.WIFI_DIRECT_GROUP) "Keep this phone nearby with Wi-Fi and iTantra on. Capacity depends on the phones; software limit: 8 people."
                            else "Keep iTantra running. The phone providing the hotspot must also keep its hotspot on.")
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (room.ready) Button(onTalk) { Text("Open Talk") }
                    DisconnectButton(app)
                }
            }
        } else {
            item {
                if (groups) Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Button({ creating = true }, enabled = !busy && network.ready, modifier = Modifier.fillMaxWidth()) { Text(if (busy) "Starting group…" else "Create a group") }
                    Hint("Choose your own name and password · up to 8 people")
                    if (saved.isNotBlank()) OutlinedButton({ app.createLan("", "", true, resume = true) }, enabled = network.ready && !busy,
                        modifier = Modifier.fillMaxWidth()) { Text("Resume $saved") }
                } else Card { Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(if (network.ready) "This phone is visible to others" else "Connect to a local network first",
                        fontWeight = FontWeight.SemiBold)
                    Hint(if (network.ready)
                        "Nearby iTantra phones on this network can see you and ask to connect. Pick one below to start."
                        else "Join the same Wi-Fi network, or turn on this phone's hotspot.")
                    if (network.ready && busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                } }
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (groups) "Find a group" else "Available iTantra devices", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    TextButton(app::refreshLan) { Text("Refresh") }
                }
                Hint(if (groups) "Groups on this network · refreshes every 5 seconds" else "Updates automatically about every 5 seconds")
            }
            val visible = nearby.filter { it.group == groups }
            if (visible.isEmpty() && network.ready) item {
                SearchingCard(
                    if (groups) "Looking for groups…" else "Looking for nearby devices…",
                    "Everyone must be on the same Wi-Fi or hotspot, with iTantra open.",
                    active = true, reducedMotion = settings.reducedMotion
                )
            }
            if (visible.isEmpty()) item { EmptyCard(if (groups) "No groups found yet" else "No available devices yet",
                if (!network.ready) "Connect to Wi-Fi or enable your hotspot first. Simply turning Wi-Fi on is not enough."
                else if (groups) "Create a named group here, or ask someone on this network to create one."
                else "Ask the other person to open Connections → One-to-one and tap Make this phone available.") }
            items(visible, key = { it.id + it.address }) { found ->
                Card(Modifier.fillMaxWidth()) { Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(found.name, fontWeight = FontWeight.SemiBold)
                        Hint((if (found.group) "Password protected · " else "Direct · ") + found.host)
                        if (found.group) Hint("${found.members} people")
                    }
                    TextButton({ app.joinLan(found) }, enabled = network.ready && !busy) { Text(if (found.group) "Join" else "Connect") }
                } }
            }
        }
        room.error?.let { item { Hint(it, MaterialTheme.colorScheme.tertiary) } }
        item {
            TextButton({ advanced = !advanced }) { Text(if (advanced) "Hide connection help" else "Can't find a group or device?") }
            if (advanced) {
                Hint(if (mode == RadioMode.WIFI_DIRECT_GROUP) "Keep the creator nearby. If the direct group disconnects, choose Find group and compare the creator's code again."
                    else "Some routers/hotspots isolate connected devices. Try hosting on the hotspot phone or use a network that allows local communication. A manual address cannot bypass isolation.")
                if (detail.isNotBlank()) Hint(detail)
                network.addresses.forEach { Hint("This phone: ${it.ip}:${LanRules.PORT}") }
                if (!room.active) {
                    OutlinedTextField(manual, { manual = it.take(32) }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Creator's local address (IP:port)") })
                    TextButton({
                        val parts = manual.trim().split(':')
                        val port = if (parts.size == 1) LanRules.PORT else parts.getOrNull(1)?.toIntOrNull() ?: 0
                        if (parts.size !in 1..2 || LanRules.ipv4(parts[0]) == null || port !in 1024..65535) app.reportError("Enter a local IPv4 address, for example 192.168.43.1:38774")
                        else app.joinLan(NearbyRoom("", "Nearby iTantra", "", parts[0], port, groups, 0, 0))
                    }, enabled = network.ready && !busy) { Text("Connect by address") }
                }
            }
        }
    }
    if (creating && !room.active) AlertDialog(onDismissRequest = { if (!busy) { creating = false; password = "" } },
        title = { Text("Create a group") },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("People on your shared Wi-Fi or hotspot will see this name and need your password to join.")
            OutlinedTextField(groupName, { groupName = it.take(48) }, Modifier.fillMaxWidth(), singleLine = true,
                label = { Text("Group name") }, placeholder = { Text("For example: Rescue Team") })
            OutlinedTextField(password, { password = it.take(64) }, Modifier.fillMaxWidth(), singleLine = true,
                label = { Text("Group password") }, supportingText = { Text("8–64 characters · not your Wi-Fi password") },
                visualTransformation = PasswordVisualTransformation())
            Hint("Up to 8 people including you. Keep this phone running while the group is in use.")
        } },
        confirmButton = { TextButton({ app.createLan(groupName, password, true); password = ""; creating = false },
            enabled = !busy && network.ready && groupName.isNotBlank() && password.length >= 8) { Text("Create group") } },
        dismissButton = { TextButton({ creating = false; password = "" }, enabled = !busy) { Text("Cancel") } })
}

/** Global dialogs remain visible when the creator is on Talk or Messages. Passwords are never saved in UI state. */
@Composable internal fun LanDialogs(app: AppRuntime) {
    val room by app.lan.state.collectAsStateWithLifecycle()
    if (room.confirmCode.isNotBlank()) {
        var matches by remember(room.confirmCode) { mutableStateOf(false) }
        var password by remember(room.confirmCode) { mutableStateOf("") }
        AlertDialog(onDismissRequest = app::disconnect, title = { Text("Join ${room.name}") },
            text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Compare with the creator's code on their Connections page before continuing.")
                Text(room.confirmCode, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(matches, { matches = it }); Text("I checked that the codes match")
                }
                if (room.group) OutlinedTextField(password, { password = it.take(64) }, Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text("Group password") }, visualTransformation = PasswordVisualTransformation())
                Hint(if (room.group) "The password is sent only after you confirm this encrypted connection."
                    else "The other person must also approve your request.")
            } },
            confirmButton = { TextButton({ app.lan.confirmHost(password); password = "" },
                enabled = matches && (!room.group || password.length >= 8)) { Text(if (room.group) "Join group" else "Request connection") } },
            dismissButton = { TextButton(app::disconnect) { Text("Cancel") } })
    } else room.requests.firstOrNull()?.let { request ->
        AlertDialog(onDismissRequest = { app.lan.approve(request.id, false) }, title = { Text("Connect with ${request.name}?") },
            text = { Text("Allow this nearby iTantra phone to exchange voice and text messages with you? Only approve the person you intended to connect with.") },
            confirmButton = { TextButton({ app.lan.approve(request.id, true) }) { Text("Allow") } },
            dismissButton = { TextButton({ app.lan.approve(request.id, false) }) { Text("Decline") } })
    }
}
