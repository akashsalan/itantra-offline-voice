package org.itantra.app

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import org.itantra.app.transport.*

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val model: TalkViewModel = viewModel()
            val settings by model.runtime.settings.collectAsStateWithLifecycle()
            ItantraTheme(settings.theme) { AppScreen(model) }
        }
    }

    @Composable private fun AppScreen(model: TalkViewModel) {
        val app = model.runtime
        val talk by app.talk.collectAsStateWithLifecycle()
        val handsFree by app.handsFree.collectAsStateWithLifecycle()
        val session by app.session.collectAsStateWithLifecycle()
        val mode by app.mode.collectAsStateWithLifecycle()
        val importProgress by app.importProgress.collectAsStateWithLifecycle()
        val settings by app.settings.collectAsStateWithLifecycle()
        val incomingEmergency by app.incomingEmergency.collectAsStateWithLifecycle()
        var tab by rememberSaveable { mutableIntStateOf(0) }
        val savedTabs = rememberSaveableStateHolder()
        var clearDialog by remember { mutableStateOf(false) }
        var relaySheet by remember { mutableStateOf(false) }
        var sosPage by rememberSaveable { mutableStateOf(false) }
        var sosBleRoute by rememberSaveable { mutableStateOf(false) }
        var sosPublicRoute by rememberSaveable { mutableStateOf(false) }
        var bluetoothAction by remember { mutableStateOf("discover") }
        var pendingWifi by remember { mutableStateOf<(() -> Unit)?>(null) }
        var micGranted by remember { mutableStateOf(checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) }
        val lifecycle = LocalLifecycleOwner.current.lifecycle
        DisposableEffect(lifecycle) {
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) micGranted = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                if (event == Lifecycle.Event.ON_START) { app.downloads.setForeground(true); app.setActivityVisible(lifecycle, true) }
                if (event == Lifecycle.Event.ON_STOP) { app.downloads.setForeground(false); app.setActivityVisible(lifecycle, false) }
            }
            lifecycle.addObserver(observer)
            app.setActivityVisible(lifecycle, lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
            onDispose { lifecycle.removeObserver(observer); app.setActivityVisible(lifecycle, false) }
        }
        val mic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
            micGranted = it
            if (!it) app.reportError("Microphone access is only needed to record. You can still receive and type messages. Enable it in App permissions when ready.")
        }
        val nearby = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            val required = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.NEARBY_WIFI_DEVICES else Manifest.permission.ACCESS_FINE_LOCATION
            val action = pendingWifi
            pendingWifi = null
            if (checkSelfPermission(required) == PackageManager.PERMISSION_GRANTED) action?.invoke()
            else app.reportError("Allow Nearby devices (Location on older Android) in App permissions, then retry your connection action.")
        }
        fun requestWifi(action: () -> Unit) {
            val required = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.NEARBY_WIFI_DEVICES else Manifest.permission.ACCESS_FINE_LOCATION
            if (checkSelfPermission(required) == PackageManager.PERMISSION_GRANTED) action()
            else {
                pendingWifi = action
                nearby.launch(if (Build.VERSION.SDK_INT >= 33) arrayOf(required, Manifest.permission.POST_NOTIFICATIONS) else arrayOf(required))
            }
        }
        val visibility = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (it.resultCode > 0) app.discover()
            else app.reportError("Bluetooth visibility was not enabled. Previously paired phones can still connect.")
        }
        fun bluetoothReady() {
            when (bluetoothAction) {
                "ble" -> app.discoverBle()
                "relay" -> app.startRelay()
                "publicRelay" -> if (sosPage && sosPublicRoute) app.startPublicRelay()
                "visible" -> runCatching {
                    visibility.launch(Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE).putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 120))
                }.onFailure { app.reportError("Bluetooth visibility is unavailable. Open Bluetooth settings and retry.") }
                else -> app.discover()
            }
        }
        val bluetoothPermissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            if (BluetoothAccess.permissions().all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }) bluetoothReady()
            else app.reportError("Allow Bluetooth Nearby devices access in App permissions, then retry.")
        }
        fun requestBluetooth(action: String) {
            bluetoothAction = action
            val required = BluetoothAccess.permissions()
            if (required.all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }) bluetoothReady()
            else bluetoothPermissions.launch((required.toList() + if (Build.VERSION.SDK_INT >= 33) listOf(Manifest.permission.POST_NOTIFICATIONS) else emptyList()).toTypedArray())
        }
        val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(app::importPack) }
        val voiceImporter = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(app::importVoice) }
        val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { it?.let(app::export) }
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            topBar = {
                if (sosPage) Spacer(Modifier.statusBarsPadding()) else
                Row(Modifier.statusBarsPadding().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
                    .fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    val compactHeader = LocalDensity.current.fontScale > 1.1f || LocalConfiguration.current.screenWidthDp < 380
                    Icon(AppIcons.Radio, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(8.dp))
                    Text("iTantra", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.width(8.dp))
                    AssistChip(onClick = { tab = 1 }, label = { Text(if (session.ready) "Connected" else "Not connected") },
                        modifier = Modifier.minimumInteractiveComponentSize(),
                        shape = AppDesign.Control,
                        leadingIcon = if (compactHeader) null else ({ Icon(if (session.ready) Icons.Default.Check else Icons.Default.Info, null, Modifier.size(18.dp)) }))
                    Spacer(Modifier.weight(1f))
                    Button({ app.endHandsFree(); sosBleRoute = !session.ready && app.relay.state.value.active;
                        sosPublicRoute = app.publicRelay.state.value.active; sosPage = true },
                        enabled = (!talk.busy || handsFree.active) && !talk.awaitingRelease,
                        modifier = Modifier.padding(start = 8.dp).minimumInteractiveComponentSize().heightIn(min = 36.dp)
                            .semantics { contentDescription = "Emergency SOS" },
                        shape = AppDesign.ToolbarAction, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                        elevation = ButtonDefaults.buttonElevation(defaultElevation = 2.dp, pressedElevation = 0.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError)) {
                        Text(if (compactHeader) "Emergency\nSOS" else "Emergency SOS",
                            fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                    }
                }
            },
            bottomBar = {
                if (!sosPage) NavigationBar(containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
                    val titles = listOf("Talk", "Connections", "Messages", "Models", "More")
                    // Keep font scaling and all five destinations; shorter visual labels avoid
                    // splitting words at large text sizes. TalkBack retains the full titles.
                    val compactLabels = LocalDensity.current.fontScale > 1.1f || LocalConfiguration.current.screenWidthDp < 380
                    val labels = if (compactLabels) listOf("Talk", "Connect", "Chat", "Models", "More") else titles
                    val icons = listOf(AppIcons.Microphone, AppIcons.Connections, AppIcons.Messages, AppIcons.Models, AppIcons.Settings)
                    titles.forEachIndexed { index, title ->
                        NavigationBarItem(selected = tab == index, onClick = { tab = index },
                            modifier = Modifier.semantics { contentDescription = title },
                            colors = NavigationBarItemDefaults.colors(indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                                selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer, selectedTextColor = MaterialTheme.colorScheme.primary,
                                unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant, unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant),
                            icon = { Icon(icons[index], null, Modifier.size(24.dp)) },
                            label = { Text(labels[index], Modifier.clearAndSetSemantics { }) })
                    }
                }
            }
        ) { padding ->
            Column(Modifier.padding(padding).consumeWindowInsets(padding).fillMaxSize()) {
                incomingEmergency?.let { alert ->
                    Surface(color = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.onErrorContainer) {
                        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                            Text("Emergency from " + alert.peerName, fontWeight = FontWeight.Bold)
                            Text(alert.text, maxLines = 2)
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                TextButton({ app.acknowledge(alert.key) }) { Text("Acknowledge") }
                                TextButton({
                                    if (org.itantra.app.relay.PublicRelaySession.owns(alert)) { sosPublicRoute = true; sosBleRoute = false; sosPage = true }
                                    else if (org.itantra.app.relay.RelaySession.owns(alert)) relaySheet = true else tab = 0
                                }) { Text("View alert") }
                            }
                        }
                    }
                }
                talk.error?.let { error ->
                    Surface(color = MaterialTheme.colorScheme.tertiaryContainer, contentColor = MaterialTheme.colorScheme.onTertiaryContainer) {
                        Row(Modifier.padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(error, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                            IconButton(app::dismissError) { Icon(Icons.Default.Close, "Dismiss error") }
                        }
                    }
                }
                if (handsFree.active && tab != 0 && !sosPage) {
                    Surface(color = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            TextButton({ tab = 0 }, Modifier.weight(1f).heightIn(min = 56.dp)) {
                                Icon(if (handsFree.muted) AppIcons.MicOff else AppIcons.Call, null, Modifier.size(22.dp))
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(if (handsFree.muted) "Hands-free · muted" else "Hands-free is active", fontWeight = FontWeight.SemiBold)
                                    Text("Return to Talk", style = MaterialTheme.typography.bodySmall)
                                }
                            }
                            IconButton(app::endHandsFree) { Icon(AppIcons.EndCall, "End hands-free") }
                        }
                    }
                }
                // Only the current page is composed: transitions do not keep old discovery effects alive.
                key(if (sosPage) "SOS" else tab) {
                    var entered by remember { mutableStateOf(false) }
                    LaunchedEffect(Unit) { entered = true }
                    val opacity by animateFloatAsState(if (entered) 1f else .7f,
                        tween(if (settings.reducedMotion) 0 else AppDesign.MotionMs), label = "Page arrival")
                    Box(Modifier.weight(1f).fillMaxWidth().graphicsLayer { alpha = opacity }) {
                        if (sosPage) SosPage(app, micGranted, { mic.launch(Manifest.permission.RECORD_AUDIO) },
                            onBack = { sosPage = false; tab = 0 },
                            onConnect = { sosPage = false; tab = 1 },
                            onRelay = { relaySheet = true },
                            onModels = { sosPage = false; tab = 3 }, initialBleRoute = sosBleRoute,
                            initialPublicRoute = sosPublicRoute, startPublicRelay = { requestBluetooth("publicRelay") },
                            onPublicRouteChanged = { sosPublicRoute = it })
                        else
                        savedTabs.SaveableStateProvider(tab) { when (tab) {
                            0 -> TalkPage(app, micGranted, { mic.launch(Manifest.permission.RECORD_AUDIO) }, { tab = 1 }, {
                                sosBleRoute = !session.ready && app.relay.state.value.active; sosPublicRoute = app.publicRelay.state.value.active; sosPage = true
                            }, onModels = { tab = 3 })
                            1 -> ConnectionsPage(app, ::requestWifi, ::requestBluetooth, { tab = 0 })
                            2 -> MessagesPage(app) { tab = 1 }
                            3 -> ModelsPage(app, { importer.launch(arrayOf("*/*")) }, { voiceImporter.launch(arrayOf("*/*")) })
                            4 -> MorePage(app, { exporter.launch("itantra-test-session.json") }, { clearDialog = true })
                        } }
                    }
                }
            }
        }
        LanDialogs(app)
        if (relaySheet) RelayPage(app, { requestBluetooth("relay") }, { relaySheet = false }, {
            relaySheet = false; sosBleRoute = true; sosPublicRoute = false; sosPage = true
        })
        importProgress?.let { ImportProgressDialog(it, talk.busy, app::dismissImportProgress) }
        if (clearDialog) AlertDialog(onDismissRequest = { clearDialog = false }, title = { Text("Clear local history?") },
            text = { Text("Disconnects the conversation and removes local messages, including unsent ones. Model packs are kept. This cannot be undone.") },
            confirmButton = { TextButton({ clearDialog = false; app.clearHistory() }) { Text("Disconnect and clear") } },
            dismissButton = { TextButton({ clearDialog = false }) { Text("Cancel") } })
    }
}

@Composable internal fun SectionTitle(title: String, subtitle: String) {
    Text(title, style = MaterialTheme.typography.headlineSmall)
    Text(subtitle, style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
}
@Composable internal fun Hint(text: String, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = color)
}
@Composable internal fun ConnectionCard(ready: Boolean, peerName: String, link: LinkState, transport: String, onClick: () -> Unit) {
    val title = when {
        ready -> "To " + peerName
        link is LinkState.Connected -> "Compare pairing codes"
        link is LinkState.Discovering -> "Finding nearby phones"
        link is LinkState.Connecting -> "Connecting…"
        link is LinkState.Failed -> "Connection needs attention"
        else -> "Choose a nearby phone"
    }
    Card(onClick = onClick, colors = CardDefaults.cardColors(containerColor =
        if (ready) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface),
        shape = AppDesign.Card, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(AppIcons.Radio, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                Hint(if (link is LinkState.Failed) link.reason else transport + " · no internet required")
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, Modifier.size(20.dp))
        }
    }
}
@Composable internal fun EmptyCard(title: String, detail: String) {
    Surface(Modifier.fillMaxWidth(), shape = AppDesign.Card, color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Hint(detail)
        }
    }
}
@Composable internal fun SettingSwitch(title: String, detail: String, checked: Boolean, change: (Boolean) -> Unit) =
    SettingSwitch(title, detail, checked, true, change)

@Composable internal fun SettingSwitch(title: String, detail: String, checked: Boolean, enabled: Boolean, change: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) { Text(title, fontWeight = FontWeight.SemiBold); Hint(detail) }
        Switch(checked, change, enabled = enabled)
    }
}
