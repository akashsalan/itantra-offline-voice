package org.itantra.app

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Process
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import org.itantra.app.asr.*
import org.itantra.app.audio.*
import org.itantra.app.core.*
import org.itantra.app.data.*
import org.itantra.app.diagnostics.Diagnostics
import org.itantra.app.models.*
import org.itantra.app.lan.*
import org.itantra.app.protocol.RadioSession
import org.itantra.app.protocol.SessionStatus
import org.itantra.app.transport.*
import org.itantra.app.relay.RelaySession
import org.itantra.app.relay.PublicRelaySession
import org.itantra.app.tts.EspeakNgEngine
import org.itantra.app.tts.TtsRouter

data class TalkState(
    val language: LanguageCode = LanguageCode.EN, val phase: String = "Preparing offline speech",
    val transcript: String = "", val ready: Boolean = false, val busy: Boolean = true,
    val recording: Boolean = false, val emergency: Boolean = false,
    val modelBytes: Long = 0, val modelLabel: String = "None", val packId: String? = null, val error: String? = null,
    val draftSent: Boolean = false, val awaitingRelease: Boolean = false,
    val loadingPackId: String? = null
)

/** SOS capture is deliberately separate: entering or recording an alert never consumes a Talk draft. */
data class SosVoiceState(
    val captureId: Long? = null, val language: LanguageCode = LanguageCode.EN,
    val text: String = "", val completed: Boolean = false, val sent: Boolean = false,
    val messageKeys: List<String> = emptyList()
)

/** Application-owned state; activity recreation does not close an active radio/speech session. */
class AppRuntime(private val application: Application) {
    /**
     * Anything unexpected in a launched coroutine becomes a visible error instead
     * of killing the process. Without this, one unguarded framework callback (for
     * example a Wi-Fi Direct listener invoked later by the system) reached
     * Android's default handler and closed the app.
     */
    private val crashGuard = CoroutineExceptionHandler { _, error ->
        if (error is CancellationException) return@CoroutineExceptionHandler
        Log.e("ItantraRuntime", "Unhandled failure in app scope", error)
        diagnostics.put("unhandledError", error.javaClass.simpleName)
        runCatching {
            fail(IllegalStateException(
                error.message?.takeIf { it.isNotBlank() }
                    ?: "Something went wrong (${error.javaClass.simpleName}). The app stayed open; try again.",
                error
            ))
        }
    }
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + crashGuard)
    val diagnostics = Diagnostics()
    private val worker = Dispatchers.IO.limitedParallelism(1)
    private val audioLock = Mutex()
    private val mutableTalk = MutableStateFlow(TalkState())
    val talk = mutableTalk.asStateFlow()
    private val mutableHandsFree = MutableStateFlow(HandsFreeState())
    val handsFree = mutableHandsFree.asStateFlow()
    private var handsFreeRearm: Job? = null
    private var resumeCaptureAfterMs = 0L
    private val activityVisibility = ForegroundActivityVisibility()
    private val activityVisible get() = activityVisibility.visible
    private val foreground = ForegroundServiceGate()
    private var captureIsHandsFree = false
    private val emergencyRetries = mutableMapOf<String, Int>()
    private val emergencyRetryJobs = mutableMapOf<String, Job>()
    private val mutableSosVoice = MutableStateFlow(SosVoiceState())
    val sosVoice = mutableSosVoice.asStateFlow()
    private val mutableSettings = MutableStateFlow(LocalSettings())
    val settings = mutableSettings.asStateFlow()
    private val preferences = AppSettings(application)
    private val models = ModelPacks(application, beforeActivate = ::validateImportedPack)
    private val mutablePacks = MutableStateFlow<List<PackStatus>>(emptyList())
    val packs = mutablePacks.asStateFlow()
    private val mutableImport = MutableStateFlow<PackImportProgress?>(null)
    val importProgress = mutableImport.asStateFlow()
    val downloads = ModelDownloads(application, scope, { settings.value.wifiOnlyDownloads }, ::installDownload)
    val store = MessageStore(application)
    val wifi = WifiDirectTransport(application, scope)
    val directGroups = WifiDirectGroups(application, scope)
    val bluetooth = BluetoothRfcommTransport(application, scope)
    val transport = SelectedTransport(wifi, bluetooth, scope)
    val ble = BleDiscovery(application, scope)
    private val mutableMode = MutableStateFlow(RadioMode.WIFI_DIRECT)
    val mode = mutableMode.asStateFlow()
    private var lanBrowsing = false
    private var lanPageVisible = false
    private val mutableLanBusy = MutableStateFlow(false)
    private var lanOperation: Job? = null
    val lanBusy = mutableLanBusy.asStateFlow()
    private val mutablePeers = MutableStateFlow<List<Peer>>(emptyList())
    val peers = mutablePeers.asStateFlow()
    private var discovery: Job? = null
    private val microphone = Microphone(application.assets)
    private var draftMeasurementId: Long? = null
    private var captureInProgress = false
    private var captureForSos = false
    private val voices = VoicePacks(application)
    private val mutableVoices = MutableStateFlow<List<VoiceStatus>>(emptyList())
    val voiceStatuses = mutableVoices.asStateFlow()
    // Bundled eSpeak stays the default and the fallback. With no voice pack
    // installed this router is a pass-through, so behaviour is unchanged.
    private val synthesizer = TtsRouter(EspeakNgEngine(application), { voices.installed(it) }) { language, detail ->
        reportError("The ${language.displayName} neural voice could not run, so the built-in voice was used. $detail")
    }
    private val player = PcmPlayer(application)
    private val alarm = EmergencyAlarm(application)
    private var recognizer: SpeechRecognizerEngine? = null
    private var radioActive = false
    private var playbackActive = false
    @Volatile private var discard = false
    private var activeMessage: MessageEntity? = null
    private val mutablePlayingKey = MutableStateFlow<String?>(null)
    val playingMessageKey = mutablePlayingKey.asStateFlow()
    private val mutableEmergency = MutableStateFlow<MessageEntity?>(null)
    val incomingEmergency = mutableEmergency.asStateFlow()
    private val wakePlayback = Channel<Unit>(Channel.CONFLATED)
    private val manualPlayback = mutableSetOf<String>()
    val relay = RelaySession(application, scope, store, diagnostics) { message ->
        receivedVoice(message)
    }
    val publicRelay = PublicRelaySession(application, scope, store, diagnostics, ::requireForegroundService, ::receivedVoice)
    val radio = RadioSession(transport, store, { settings.value }, { packs.value.filter { it.installed }.map { it.language }.distinct() }, scope, diagnostics) { message ->
        diagnostics.received(message)
        receivedVoice(message)
    }
    val localDiscovery = LanDiscovery(application, scope,
        ConversationNetworks(LanNetworks(application), directGroups) { mode.value == RadioMode.WIFI_DIRECT_GROUP })
    val lan = LanSession(application, scope, store, { settings.value.name }, localDiscovery, diagnostics, { message ->
        diagnostics.received(message)
        receivedVoice(message)
    })
    val session = combine(mode, radio.state, lan.state) { selected, direct, room ->
        if (!selected.isLan) direct else SessionStatus(peerId = room.roomId, peerName = room.title,
            ready = room.ready, localConfirmed = room.connected, remoteConfirmed = room.connected,
            remoteChat = true, remoteTts = LanguageCode.entries.toSet(), error = room.error)
    }.stateIn(scope, SharingStarted.Eagerly, SessionStatus())
    val link = combine(mode, transport.state, lan.state) { selected, direct, room ->
        if (!selected.isLan) direct else when {
            room.connected -> LinkState.Connected(Peer(room.roomId, room.title))
            room.active -> LinkState.Connecting(Peer(room.roomId, room.name))
            room.error != null -> LinkState.Failed(room.error)
            else -> LinkState.Disconnected
        }
    }.stateIn(scope, SharingStarted.Eagerly, LinkState.Disconnected)
    private val canSend get() = if (mode.value.isLan) lan.state.value.ready else radio.state.value.ready
    private val destination get() = if (mode.value.isLan) lan.state.value.title else radio.state.value.peerName
    private suspend fun enqueueVoice(text: String, language: LanguageCode, emergency: Boolean, speechEnd: Long = 0,
        measurementId: Long? = null, mayEnqueue: () -> Boolean = { true }): List<String> =
        if (mode.value.isLan) lan.enqueue(text, language, emergency, speechEnd, measurementId = measurementId, mayEnqueue = mayEnqueue)
        else radio.enqueue(text, language, emergency, speechEnd, measurementId = measurementId, mayEnqueue = mayEnqueue)

    init {
        diagnostics.initialize(application)
        scope.launch {
            try {
                mutableSettings.value = preferences.initialize()
                val restored = ConnectionChoice.restore(settings.value.connectionGroup, settings.value.connectionMethod)
                mutableMode.value = restored.method
                if (!restored.method.isLan) transport.select(restored.method)
                launch { preferences.flow.collect { mutableSettings.value = it; wakePlayback.trySend(Unit) } }
                radio.recoverOutbox()
                lan.recoverOutbox()
                relay.recover()
                withContext(worker) {
                    val warming = SystemClock.elapsedRealtime()
                    runCatching { synthesizer.prepare() }
                        .onSuccess { diagnostics.put("ttsWarmupMs", SystemClock.elapsedRealtime() - warming) }
                        .onFailure { diagnostics.put("ttsWarmupFailed", true) }
                    if (BuildConfig.PRELOADED) {
                        for ((profile, file) in listOf(PackCatalog.englishMoonshineSmall to "en.itpack",
                            PackCatalog.englishMoonshineTiny to "en-low-end.itpack", PackCatalog.hindi to "hi.itpack")) {
                            val existing = runCatching { models.installed(profile.language) }.getOrNull()
                            val installed = runCatching { models.installedProfile(profile) }.getOrNull()
                            if (installed == null && models.shouldInstallBundled(profile)) {
                                mutableTalk.update { it.copy(phase = "Installing ${profile.label} - checking SHA-256") }
                                try { models.importAsset(file, ::reportImportProgress,
                                    makeActive = PackCatalog.activateBundled(profile, existing?.packId)) }
                                catch (error: Exception) {
                                    if (existing == null) throw error
                                    mutableTalk.update { it.copy(error = "Additional model could not be installed. Your selected model is kept; retry import from Models.") }
                                }
                            }
                        }
                        // Preloaded also bundles the English and Hindi neural voices.
                        // A voice failure must never block speech: eSpeak remains.
                        for (language in VoiceCatalog.bundled) {
                            val profile = VoiceCatalog.preferred(language) ?: continue
                            val installed = runCatching { voices.installedProfile(profile) }.getOrNull()
                            if (installed == null && voices.shouldInstallBundled(profile)) {
                                mutableTalk.update { it.copy(phase = "Installing ${profile.label} - checking SHA-256") }
                                runCatching {
                                    voices.importAsset(VoiceCatalog.bundledAsset(language), ::reportImportProgress)
                                }.onFailure {
                                    mutableTalk.update { state ->
                                        state.copy(error = "The ${language.displayName} neural voice could not be installed. The built-in voice is used; retry from Models.")
                                    }
                                }
                            }
                        }
                    }
                    mutableImport.update { it?.copy(stage = ImportStage.LOADING, file = null) }
                    refreshPacks()
                    refreshVoices()
                    // Start on English, but never start on a language with no model
                    // when the phone actually has one: that left push-to-talk
                    // disabled with no obvious reason. Receiving is unaffected either
                    // way; this only picks the language the microphone starts on.
                    load(startupLanguage())
                    mutableImport.update { it?.copy(stage = ImportStage.READY) }
                }
            } catch (error: Exception) { fail(error) }
            finally { mutableTalk.update { it.copy(busy = false) }; wakePlayback.trySend(Unit) }
        }
        scope.launch { while (isActive) {
            withContext(Dispatchers.IO) { diagnostics.sampleMemory(); diagnostics.sampleCpu() }
            delay(if (talk.value.busy || handsFree.value.active) 1000 else 5000)
        } }
        scope.launch {
            combine(talk, handsFree, session) { speech, free, connection -> when {
                free.active && !free.muted && speech.phase == "Hands-free: waiting for speech" -> "HANDS_FREE_WAITING"
                speech.busy -> "ACTIVE"
                free.active && free.muted -> "HANDS_FREE_MUTED"
                connection.ready -> "IDLE_CONNECTED"
                else -> "IDLE_DISCONNECTED"
            } }.distinctUntilChanged().collect(diagnostics::resourceMode)
        }
        scope.launch { store.messages.collect { diagnostics.updateMessages(it); wakePlayback.trySend(Unit) } }
        scope.launch { diagnostics.state.collect { measurements ->
            if (!captureForSos && talk.value.draftSent && microphone.held && measurements.sent.any { it.id == draftMeasurementId && (it.framedBytes ?: 0) > 0 })
                mutableTalk.update { it.copy(phase = "Sent—release to talk again") }
        } }
        scope.launch { playbackLoop() }
        scope.launch {
            combine(mode, radio.state, lan.state) { _, _, _ -> emergencyAudienceKey(false) }
                .distinctUntilChanged().collect { audience ->
                    if (handsFree.value.active && audience != handsFree.value.audience) {
                        endHandsFree()
                        reportError("Hands-free stopped because the connection or recipients changed. Review the conversation and start again.")
                    }
                }
        }
        scope.launch { ble.state.map { it.scanning || it.advertising }.distinctUntilChanged().collect { service() } }
        scope.launch { relay.state.map { it.active }.distinctUntilChanged().collect { service(); wakePlayback.trySend(Unit) } }
        scope.launch { publicRelay.state.map { it.active }.distinctUntilChanged().collect { active ->
            if (!active) {
                clearPublicPlayback()
                val alert = mutableEmergency.value
                if (alert != null && PublicRelaySession.owns(alert)) { mutableEmergency.value = null; SpeechService.cancelAlert(application, alert.key) }
            }
            service(); wakePlayback.trySend(Unit)
        } }
        scope.launch { lan.state.map { it.active }.distinctUntilChanged().collect { service(); wakePlayback.trySend(Unit) } }
        scope.launch { directGroups.state.map { it.formed }.distinctUntilChanged().collect { formed ->
            if (!formed && mode.value == RadioMode.WIFI_DIRECT_GROUP && lan.state.value.active) {
                lan.disconnect()
                reportError("Direct group disconnected. Rejoin or create a group; message history is kept.")
            }
            service()
        } }
    }
    private fun fail(error: Throwable) {
        mutableTalk.update { it.copy(error = error.message ?: "Operation failed", phase = "Action needed") }
        mutableImport.update { progress ->
            if (progress != null && progress.stage != ImportStage.READY)
                progress.copy(stage = ImportStage.FAILED, error = error.message ?: "Operation failed") else progress
        }
    }
    fun reportError(message: String) { fail(IllegalStateException(message)) }
    fun dismissError() { mutableTalk.update { it.copy(error = null) } }
    fun dismissImportProgress() { if (!talk.value.busy) mutableImport.value = null }
    fun setActivityVisible(owner: Any, visible: Boolean) {
        activityVisibility.update(owner, visible)
        if (visible) foreground.allowRetry()
    }
    internal fun foregroundServiceCreated(): Long = foreground.created()
    private fun foregroundDemand() = ForegroundServiceDemand(
        captureWork = captureInProgress, recording = talk.value.recording,
        handsFree = handsFree.value.active, muted = handsFree.value.muted, playback = playbackActive,
        connected = radioActive || lanBrowsing || lan.state.value.active || directGroups.state.value.active ||
            relay.state.value.active || publicRelay.state.value.active || ble.state.value.scanning || ble.state.value.advertising)
    internal fun foregroundServiceDemand(instance: Long, leaseId: Long): ForegroundServiceDemand? =
        foregroundDemand().takeIf { it.needed && foreground.accepts(instance, leaseId) }
    internal fun foregroundServicePromoted(instance: Long, leaseId: Long) { foreground.promoted(instance, leaseId) }
    internal fun foregroundServiceFailed(error: Throwable, instance: Long? = null, leaseId: Long? = null) {
        if (instance != null && (leaseId == null || !foreground.accepts(instance, leaseId))) return
        val firstFailure = foreground.failure == null
        val failure = foreground.failed(IllegalStateException(
            "Foreground audio unavailable (${error.javaClass.simpleName}): ${error.message ?: "No Android error detail"}. " +
                "Keep iTantra open and retry. Check permissions if Android reports a permission error.", error))
        if (firstFailure) {
            Log.e("ItantraForeground", "Foreground service failure", failure)
            diagnostics.put("foregroundFailureType", error.javaClass.simpleName)
        }
        mutableHandsFree.value = handsFree.value.end()
        handsFreeRearm?.cancel(); handsFreeRearm = null
        if (publicRelay.state.value.active) publicRelay.stop("BLE SOS stopped because Android foreground service was unavailable. Reopen the app and retry.")
        interruptAudio()
        // Stop even when a failed capture is still unwinding. The gate marks this
        // teardown expected, and keeps the original error for its awaiting caller.
        runCatching { application.stopService(Intent(application, SpeechService::class.java)) }
        fail(failure)
    }
    internal fun foregroundServiceStopped(instance: Long) {
        when (foreground.destroyed(instance)) {
            ServiceDestruction.UNEXPECTED -> foregroundServiceFailed(IllegalStateException("Android stopped the active speech service"))
            ServiceDestruction.EXPECTED -> if (foreground.waitingForPromotion && foregroundDemand().needed) {
                // Android may deliver an old onDestroy after the user's next press.
                // Reissue only an already-pending lease, never restart ended consent.
                service()
            }
            ServiceDestruction.STALE -> Unit
        }
    }
    internal fun refreshForegroundService() { service() }
    fun setHandsFreeMode(enabled: Boolean) {
        if (!enabled) endHandsFree()
        saveSettings(settings.value.copy(handsFreeMode = enabled))
    }
    fun startHandsFree() {
        if (!activityVisible || talk.value.busy || !talk.value.ready || talk.value.awaitingRelease) return
        if (!talk.value.draftSent && talk.value.transcript.isNotBlank()) {
            reportError("Send or discard your existing draft before starting hands-free."); return
        }
        try {
            foreground.allowRetry()
            check(application.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                "Allow microphone access before starting hands-free."
            }
            mutableHandsFree.value = handsFree.value.start(emergencyAudienceKey(false))
            mutableTalk.update { it.copy(emergency = false, error = null, phase = "Hands-free starting") }
            // Request microphone FGS while the initiating Activity is visible; keep
            // that type for the whole session, including temporary playback/mute.
            if (!service()) throw foreground.failure ?: IllegalStateException("Foreground service request failed")
            wakePlayback.trySend(Unit)
        } catch (error: Exception) { endHandsFree(); fail(error) }
    }
    fun endHandsFree() {
        mutableHandsFree.value = handsFree.value.end()
        handsFreeRearm?.cancel(); handsFreeRearm = null
        if (captureIsHandsFree && captureInProgress) interruptAudio()
        if (!talk.value.busy) mutableTalk.update { it.copy(phase = "Hands-free ended") }
        service()
    }
    fun muteHandsFree(muted: Boolean) {
        if (!handsFree.value.active) return
        mutableHandsFree.value = handsFree.value.mute(muted)
        handsFreeRearm?.cancel(); handsFreeRearm = null
        if (captureIsHandsFree && captureInProgress) interruptAudio()
        if (!talk.value.busy) mutableTalk.update { it.copy(phase = if (muted) "Hands-free muted" else "Hands-free waiting") }
        service(); wakePlayback.trySend(Unit)
    }
    private fun scheduleHandsFree() {
        handsFreeRearm?.cancel(); handsFreeRearm = null
        val current = handsFree.value
        if (!current.active || current.muted || talk.value.busy || !talk.value.ready || emergencyRetryJobs.isNotEmpty()) return
        handsFreeRearm = scope.launch {
            delay((resumeCaptureAfterMs - SystemClock.elapsedRealtime()).coerceAtLeast(0))
            if (handsFree.value.canSend(current.generation, emergencyAudienceKey(false)) && nextPlayback() == null)
                beginRecording(false, current.generation)
        }
    }
    private fun receivedVoice(message: MessageEntity) {
        if (message.channel != "VOICE") return
        if (message.emergency) {
            mutableEmergency.value = message
            SpeechService.alert(application, message)
        }
        if (EmergencyAudioPolicy.preempts(message.emergency,
                activeMessage?.let { it.emergency && it.direction == "IN" } == true,
                captureIsHandsFree && captureInProgress)) interruptAudio()
        wakePlayback.trySend(Unit)
    }
    fun connectionGuidance(force: Boolean = false) {
        if ((!force && !settings.value.spokenGuidance) || talk.value.busy) return
        val accessibility = application.getSystemService(android.view.accessibility.AccessibilityManager::class.java)
        if (!force && accessibility?.isTouchExplorationEnabled == true) return
        val hindi = talk.value.language == LanguageCode.HI
        val words = if (hindi) "एक व्यक्ति या समूह चुनें। फिर कनेक्शन का तरीका चुनें। इंटरनेट की आवश्यकता नहीं है।"
            else "Choose one person or a group, then choose a connection method. No internet is required."
        speak(words, if (hindi) LanguageCode.HI else LanguageCode.EN)
    }
    private fun reportImportProgress(progress: PackImportProgress) {
        mutableImport.value = progress
        diagnostics.put("model_import_percent", progress.percent ?: 0)
        mutableTalk.update { it.copy(phase = progress.stage.label + (progress.percent?.let { percent -> " · $percent%" } ?: "")) }
    }
    private suspend fun refreshVoices() {
        mutableVoices.value = runCatching { voices.statuses() }.getOrElse { emptyList() }
    }
    private suspend fun refreshPacks() {
        mutablePacks.value = models.statuses()
        diagnostics.installedModelStorage(java.io.File(application.noBackupFilesDir, "models")
            .walkTopDown().filter { it.isFile }.sumOf { it.length() })
    }
    private suspend fun validateImportedPack(pack: AsrPack) = withContext(worker) {
        if (PackCatalog.resolve(pack.language, pack.packId) !in PackCatalog.englishChoices) return@withContext
        val previousLanguage = talk.value.language
        recognizer?.close(); recognizer = null
        diagnostics.activeModel(null)
        mutableTalk.update { it.copy(ready = false, loadingPackId = pack.packId, phase = "Checking English speech model") }
        val candidate = speechRecognizerFor(pack)
        try { candidate.load(pack) }
        catch (error: Exception) {
            candidate.close()
            // The import has not touched the active pointer. Restore the previous
            // recognizer without deleting or re-importing any existing phone model.
            runCatching { load(previousLanguage) }
            throw error
        } finally { candidate.close(); mutableTalk.update { it.copy(loadingPackId = null) } }
    }
    /**
     * English when it has a model, otherwise the first language that does.
     *
     * Falls back to English when nothing is installed, so the Models tab still
     * points at the pack most users want first.
     */
    private suspend fun startupLanguage(): LanguageCode {
        suspend fun usable(language: LanguageCode) = runCatching {
            models.installed(language) != null ||
                PackCatalog.choices(language).any { models.installedProfile(it) != null }
        }.getOrDefault(false)
        if (usable(LanguageCode.EN)) return LanguageCode.EN
        return LanguageCode.entries.firstOrNull { it != LanguageCode.EN && usable(it) } ?: LanguageCode.EN
    }
    private suspend fun load(language: LanguageCode, requested: AsrPack? = null) {
        recognizer?.close(); recognizer = null
        diagnostics.activeModel(null)
        mutableTalk.update { it.copy(language = language, ready = false, modelBytes = 0, modelLabel = "None", packId = null, loadingPackId = null) }
        val active = requested ?: models.installed(language)
        val pack = active ?: PackCatalog.choices(language).firstNotNullOfOrNull { models.installedProfile(it) }
        if (pack == null) {
            mutableTalk.update { it.copy(phase = if (language == LanguageCode.OR) "Import the experimental Odia pack to try speech recognition." else "Import a ${language.displayName} pack to speak. Receiving still works.") }
            return
        }
        mutableTalk.update { it.copy(loadingPackId = pack.packId) }
        val candidate = speechRecognizerFor(pack)
        try {
            val loaded = candidate.load(pack); recognizer = candidate
            if (active == null) models.activateInstalled(pack)
            diagnostics.activeModel(pack.bytes)
            val profile = PackCatalog.resolve(language, pack.packId)
            diagnostics.put("model_load_ms", loaded.elapsedMs); diagnostics.put("model_bytes", pack.bytes); diagnostics.put("asr_pack_id", pack.packId)
            diagnostics.put("asr_experimental", profile.experimental)
            diagnostics.put("asr_engine", pack.engine)
            mutableTalk.update { it.copy(ready = true, loadingPackId = null, modelBytes = pack.bytes, modelLabel = profile.label, packId = profile.id,
                phase = when {
                    profile.experimental -> "Experimental Odia loaded - review each transcript"
                    profile == PackCatalog.englishLegacy -> "Legacy English loaded - updated pack available"
                    else -> "Ready to talk"
                }) }
        } catch (error: Throwable) {
            candidate.close()
            recognizer = null
            mutableTalk.update { it.copy(loadingPackId = null) }
            if (requested == null && pack.packId == PackCatalog.englishMoonshineSmall.id && error is Exception) {
                val previous = runCatching { models.installedProfile(PackCatalog.englishParakeet) }.getOrNull()
                if (previous != null) {
                    models.activateInstalled(previous); load(language); refreshPacks()
                    mutableTalk.update { it.copy(error = "Moonshine could not load. Previous English model restored; retry import from Models.") }
                    return
                }
            }
            throw error
        }
    }
    fun select(language: LanguageCode, packId: String? = null) {
        if (talk.value.busy || !audioLock.tryLock()) return
        val previousLanguage = talk.value.language
        mutableTalk.update { it.copy(busy = true, ready = false, error = null, phase = "Loading ${language.displayName}") }
        scope.launch {
            try { withContext(worker) {
                if (packId == null) load(language) else {
                    val profile = PackCatalog.resolve(language, packId)
                    val pack = requireNotNull(models.installedProfile(profile)) { "Import ${profile.label} from Models first." }
                    // Load on the single worker before changing the saved selection.
                    load(language, pack)
                    models.activateInstalled(pack)
                }
                refreshPacks()
            } }
            catch (error: Exception) {
                withContext(worker) { runCatching { load(previousLanguage) }; refreshPacks() }
                fail(error)
            }
            finally { mutableTalk.update { it.copy(busy = false) }; audioLock.unlock(); wakePlayback.trySend(Unit) }
        }
    }
    fun importPack(uri: Uri) {
        if (talk.value.busy || !audioLock.tryLock()) return
        mutableTalk.update { it.copy(busy = true, error = null, phase = "Importing pack - verifying SHA-256") }
        mutableImport.value = PackImportProgress()
        scope.launch {
            try { withContext(worker) {
                val language = models.import(uri, ::reportImportProgress)
                mutableImport.update { it?.copy(stage = ImportStage.LOADING, file = null) }
                refreshPacks()
                load(language)
                mutableImport.update { it?.copy(stage = ImportStage.READY) }
            } }
            catch (error: Exception) { fail(error) }
            finally { mutableTalk.update { it.copy(busy = false) }; audioLock.unlock(); wakePlayback.trySend(Unit) }
        }
    }
    private suspend fun installDownload(file: java.io.File) {
        withContext(Dispatchers.Main.immediate) {
            check(!talk.value.busy && audioLock.tryLock()) { "Speech is active. Finish talking, then tap Retry to install the verified download." }
            mutableTalk.update { it.copy(busy = true, error = null, phase = "Installing verified download") }
            try {
                withContext(worker) {
                    val language = models.importStream(::reportImportProgress) { file.inputStream() }
                    refreshPacks(); load(language)
                    mutableImport.update { it?.copy(stage = ImportStage.READY) }
                }
            } finally { mutableTalk.update { it.copy(busy = false) }; audioLock.unlock(); wakePlayback.trySend(Unit) }
        }
    }
    /**
     * Voice actions are deliberately separate from recogniser actions: installing
     * or removing a voice never touches an imported speech-recognition model, and
     * never changes the selected speech language.
     */
    fun importVoice(uri: Uri) {
        if (talk.value.busy || !audioLock.tryLock()) return
        mutableTalk.update { it.copy(busy = true, error = null, phase = "Importing voice - verifying SHA-256") }
        mutableImport.value = PackImportProgress()
        scope.launch {
            try {
                withContext(worker) {
                    val language = voices.import(uri, ::reportImportProgress)
                    synthesizer.invalidate(language)
                    refreshVoices()
                    mutableImport.update { it?.copy(stage = ImportStage.READY) }
                }
            } catch (error: Exception) { fail(error) }
            finally { mutableTalk.update { it.copy(busy = false) }; audioLock.unlock(); wakePlayback.trySend(Unit) }
        }
    }
    fun selectVoice(language: LanguageCode, packId: String?) {
        if (talk.value.busy || !audioLock.tryLock()) return
        mutableTalk.update { it.copy(busy = true, error = null) }
        scope.launch {
            try {
                withContext(worker) {
                    if (packId == null) voices.useEspeak(language) else {
                        val profile = VoiceCatalog.resolve(language, packId)
                        val pack = requireNotNull(voices.installedProfile(profile)) {
                            "Import ${profile.label} from Models first."
                        }
                        voices.activateInstalled(pack)
                    }
                    synthesizer.invalidate(language)
                    refreshVoices()
                }
            } catch (error: Exception) { fail(error) }
            finally { mutableTalk.update { it.copy(busy = false) }; audioLock.unlock(); wakePlayback.trySend(Unit) }
        }
    }
    fun removeVoice(language: LanguageCode, packId: String) {
        if (talk.value.busy || !audioLock.tryLock()) {
            reportError("Finish speaking before removing a voice."); return
        }
        mutableTalk.update { it.copy(busy = true) }
        scope.launch {
            try {
                withContext(worker) {
                    // Release any resident session first so the files are not in use.
                    synthesizer.invalidate(language)
                    voices.remove(language, packId)
                    refreshVoices()
                }
            } catch (error: Exception) { fail(error) }
            finally { mutableTalk.update { it.copy(busy = false) }; audioLock.unlock(); wakePlayback.trySend(Unit) }
        }
    }
    fun removeModel(language: LanguageCode, packId: String) {
        if (talk.value.busy || (talk.value.ready && talk.value.packId == packId) || !audioLock.tryLock()) {
            reportError("Select a different installed model before removing this one."); return
        }
        mutableTalk.update { it.copy(busy = true) }
        scope.launch {
            try { withContext(worker) { models.remove(language, packId); refreshPacks() } }
            catch (error: Exception) { fail(error) }
            finally { mutableTalk.update { it.copy(busy = false) }; audioLock.unlock(); wakePlayback.trySend(Unit) }
        }
    }
    fun setEmergency(value: Boolean) { if (!talk.value.recording) mutableTalk.update { it.copy(emergency = value) } }
    fun editTranscript(value: String) { if (!talk.value.busy) {
        if (value.isBlank()) { if (!talk.value.draftSent) draftMeasurementId?.let(diagnostics::discarded); draftMeasurementId = null }
        mutableTalk.update { it.copy(transcript = value.take(8000), draftSent = false) }
    } }
    private fun service(): Boolean {
        return try {
            val demand = foregroundDemand()
            if (!demand.needed) {
                foreground.requestStop() // Before stopService: onDestroy may be delivered later.
                application.stopService(Intent(application, SpeechService::class.java))
                true
            } else if (foreground.failure != null) false
            else {
                val lease = foreground.requestStart(demand.capabilities)
                checkNotNull(application.startForegroundService(Intent(application, SpeechService::class.java)
                    .putExtra("leaseId", lease.id))) { "Android could not resolve the speech service" }
                true
            }
        } catch (error: Exception) { foregroundServiceFailed(error); false }
    }
    private suspend fun requireForegroundService() {
        if (!service()) throw foreground.failure ?: IllegalStateException("Foreground service request failed")
        val lease = checkNotNull(foreground.lease) { "No foreground speech operation was requested" }
        try {
            // startForegroundService only queues startup; microphone/playback must
            // wait until SpeechService actually completes startForeground.
            withTimeout(4000) { lease.ready.await() }
        } catch (error: TimeoutCancellationException) {
            foregroundServiceFailed(IllegalStateException("Speech service did not become foreground within 4 seconds", error))
            throw checkNotNull(foreground.failure)
        }
    }
    fun selectTransport(mode: RadioMode) {
        chooseConnection(settings.value.connectionGroup, mode)
    }
    fun chooseConnection(group: Boolean, mode: RadioMode) {
        if (this.mode.value == mode && settings.value.connectionGroup == group) return
        if (mode !in ConnectionChoice.methods(group)) return
        if (!ConnectionChoice.canChange(session.value.ready, lan.state.value.active,
                link.value is LinkState.Connecting || link.value is LinkState.Connected, talk.value.busy || mutableLanBusy.value)) {
            reportError("Finish the current action and disconnect before changing conversation type or method."); return
        }
        mutableLanBusy.value = true
        scope.launch {
            try {
                disconnectNow()
                if (!mode.isLan) transport.select(mode)
                mutableMode.value = mode
                preferences.save(settings.value.copy(connectionGroup = group, connectionMethod = mode.name))
                service()
            } catch (error: Exception) { fail(error) } finally { mutableLanBusy.value = false }
        }
    }
    fun browseLan(enabled: Boolean) {
        lanPageVisible = enabled
        scope.launch { try {
            lanBrowsing = lanPageVisible && mode.value.isLan
            localDiscovery.browse(lanBrowsing); service()
        } catch (error: Exception) { fail(error) } }
    }
    fun refreshLan() { scope.launch { try { localDiscovery.refresh() } catch (error: Exception) { fail(error) } } }

    /**
     * Makes this phone visible for a one-to-one local connection without the user
     * tapping anything. Idempotent and deliberately quiet: it never touches an
     * active room, never applies to password-protected groups, and a failure is
     * logged rather than shown, because the user did not ask for it directly.
     */
    fun autoHostDirect() {
        val method = mode.value
        // Wi-Fi Direct groups need Android to form the group first, so they keep
        // their explicit flow. Plain LAN and hotspot can advertise immediately.
        if (method != RadioMode.SAME_WIFI && method != RadioMode.HOTSPOT) return
        if (mutableLanBusy.value || settings.value.connectionGroup) return
        if (lan.state.value.active) return
        mutableLanBusy.value = true
        lanOperation = scope.launch {
            try { lan.host(settings.value.name, "", group = false, label = method.label) }
            catch (error: Exception) {
                if (error is CancellationException) throw error
                Log.i("ItantraLan", "Automatic availability did not start", error)
            } finally { mutableLanBusy.value = false; service() }
        }
    }
    fun createLan(name: String, password: String, group: Boolean, resume: Boolean = false) {
        if (mutableLanBusy.value) return
        mutableLanBusy.value = true
        lanOperation = scope.launch { try {
            check(mode.value.isLan)
            if (mode.value == RadioMode.WIFI_DIRECT_GROUP) { directGroups.create(); service() }
            lan.host(name, password, group, if (mode.value == RadioMode.WIFI_DIRECT_GROUP) "Wi-Fi Direct group" else mode.value.label, resume)
            if (mode.value == RadioMode.WIFI_DIRECT_GROUP) directGroups.advertise(lan.state.value.name, lan.state.value.roomId)
            service()
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            if (mode.value == RadioMode.WIFI_DIRECT_GROUP) { lan.disconnect(); directGroups.stop() }
            fail(error)
        } finally { mutableLanBusy.value = false; service() } }
    }
    fun discoverDirectGroups() {
        if (mutableLanBusy.value) return
        mutableLanBusy.value = true
        lanOperation = scope.launch { try { directGroups.discover(); service() }
            catch (error: Exception) { if (error !is CancellationException) fail(error) }
            finally { mutableLanBusy.value = false } }
    }
    fun stopDirectGroupDiscovery() { scope.launch { directGroups.stopDiscovery() } }
    fun joinDirectGroup(peer: DirectGroupPeer) {
        if (mutableLanBusy.value || mode.value != RadioMode.WIFI_DIRECT_GROUP) return
        mutableLanBusy.value = true
        lanOperation = scope.launch { try {
            val address = directGroups.join(peer)
            localDiscovery.refresh()
            lan.join(NearbyRoom(peer.roomId, peer.groupName, peer.phone, address, LanRules.PORT, true, 0, 0), "Wi-Fi Direct group")
        } catch (error: Exception) { if (error is CancellationException) throw error; directGroups.stop(); fail(error) }
        finally { mutableLanBusy.value = false; service() } }
    }
    fun joinLan(room: NearbyRoom) {
        if (mutableLanBusy.value) return
        mutableLanBusy.value = true
        scope.launch { try {
        check(mode.value.isLan); lan.join(room, mode.value.label); service()
    } catch (error: Exception) { fail(error) } finally { mutableLanBusy.value = false } } }
    fun acceptLanJoins(value: Boolean) { scope.launch { try { lan.setAccepting(value) } catch (error: Exception) { fail(error) } } }
    fun discoverBle() {
        if (publicRelay.state.value.active) { reportError("Stop public BLE SOS before starting separate BLE discovery."); return }
        try {
            ble.start(); service()
        } catch (error: Exception) { ble.stop(); service(); fail(error) }
    }
    fun stopBle() { ble.stop(); service() }
    fun discover() {
        if (settings.value.deviceId.isEmpty()) return
        discovery?.cancel()
        discovery = scope.launch {
            try {
                radioActive = true; service(); mutablePeers.value = emptyList()
                transport.discover().collect { peer -> mutablePeers.update { old -> (old.filterNot { it.id == peer.id } + peer).takeLast(64) } }
            } catch (error: Exception) {
                if (error !is CancellationException) {
                    transport.disconnect(); radioActive = false; service(); fail(error)
                }
            }
        }
    }
    /**
     * Held so a second tap is ignored rather than queued, and so Disconnect can
     * cancel it. Connecting now rediscovers the peer first, which takes seconds;
     * without this, a queued second connect would tear down the first one's work,
     * and Disconnect would wait behind the transport lock until it finished.
     */
    private var connectJob: Job? = null
    fun connect(peer: Peer) {
        if (connectJob?.isActive == true) return
        connectJob = scope.launch { try {
            ble.stop(); radioActive = true; service(); transport.connect(peer)
        } catch (error: Exception) { if (error !is CancellationException) fail(error) } }
    }
    fun confirmPairing() { scope.launch { try { radio.confirmPairing() } catch (error: Exception) { fail(error) } } }
    fun disconnect() { endHandsFree(); interruptAudio(); scope.launch { try {
        disconnectNow()
        // Leaving a room must not silently stop the visible Connections page's five-second refresh.
        if (lanPageVisible && mode.value.isLan) {
            lanBrowsing = true; localDiscovery.browse(true); service()
        }
    } catch (error: Exception) { fail(error) } } }
    private suspend fun disconnectNow() {
        endHandsFree()
        for ((key, job) in emergencyRetryJobs.toMap()) {
            job.cancel()
            store.mutate(key) { if (it.playback == "WAITING_AUDIO") it.playback = "INTERRUPTED" }
        }
        emergencyRetryJobs.clear(); emergencyRetries.clear()
        lanOperation?.cancelAndJoin(); lanOperation = null
        // Before transport.disconnect(): it takes the same lock a pending connect holds.
        connectJob?.cancelAndJoin(); connectJob = null
        discovery?.cancel(); discovery = null
        lanBrowsing = false; lan.disconnect(); localDiscovery.stop()
        directGroups.stop()
        ble.stop(); transport.disconnect(); radioActive = false; mutablePeers.value = emptyList(); service()
    }
    fun startRecording() { if (!handsFree.value.active) beginRecording(false) }
    fun startEmergencyRecording(): Boolean = beginRecording(true)
    private fun beginRecording(forSos: Boolean, handsFreeGeneration: Long? = null): Boolean {
        val automatic = handsFreeGeneration != null
        if (!automatic && handsFree.value.active) return false
        if (!talk.value.ready || talk.value.busy || microphone.held || !audioLock.tryLock()) return false
        if (!automatic) {
            // Manual PTT/SOS is invoked by its visible UI control. Do not reject
            // the user's gesture using a potentially transitioning lifecycle flag;
            // Android still enforces permissions and foreground-service eligibility.
            foreground.allowRetry() // A new explicit press can retry; no activity restart is required.
        }
        if (application.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            audioLock.unlock(); reportError("Allow microphone access in App permissions before recording."); return false
        }
        val language = talk.value.language
        val emergency = !automatic && talk.value.emergency
        val finishAfterPause = settings.value.finishAfterPause
        val armed = if (automatic) microphone.armHandsFree(settings.value.handsFreePauseMs) else microphone.arm(finishAfterPause)
        if (!armed) { audioLock.unlock(); return false }
        captureInProgress = true
        captureForSos = forSos
        captureIsHandsFree = automatic
        discard = false
        if (forSos) {
            if (!sosVoice.value.sent) sosVoice.value.captureId?.let(diagnostics::discarded)
        } else if (!talk.value.draftSent) draftMeasurementId?.let(diagnostics::discarded)
        val modelName = when (talk.value.packId) {
            PackCatalog.englishParakeet.id -> "Parakeet 110M CTC INT8"
            PackCatalog.englishMoonshineSmall.id -> "Moonshine Small Streaming (quantized)"
            PackCatalog.englishMoonshineTiny.id -> "Moonshine Tiny Streaming (quantized; low-end option)"
            else -> talk.value.modelLabel
        }
        val event = diagnostics.beginCapture(language.code, modelName, talk.value.packId, automatic)
        if (forSos) mutableSosVoice.value = SosVoiceState(captureId = event, language = language)
        else draftMeasurementId = event
        mutableTalk.update { it.copy(busy = true, recording = true, awaitingRelease = !automatic, error = null,
            transcript = if (forSos) it.transcript else "", draftSent = if (forSos) it.draftSent else false,
            phase = if (automatic) "Hands-free: waiting for speech" else "Listening") }
        scope.launch {
            var transcribed = false
            var liveAudio: Channel<ShortArray>? = null
            var liveConsumer: Deferred<Result<Unit>>? = null
            try {
                requireForegroundService()
                val cpu = Process.getElapsedCpuTime()
                val engine = checkNotNull(recognizer)
                if (engine.acceptsLivePcm) {
                    withContext(worker) { engine.reset() }
                    // At most the 15-second capture budget (750 x 20 ms blocks).
                    // Never block AudioRecord/VAD on inference or silently drop PCM.
                    val queue = Channel<ShortArray>(750).also { liveAudio = it }
                    liveConsumer = async(worker) {
                        try {
                            for (pcm in queue) { ensureActive(); if (discard) break; engine.acceptPcm16(pcm) }
                            Result.success(Unit)
                        } catch (error: Exception) {
                            queue.close(error); microphone.stop()
                            if (error is CancellationException) throw error
                            Result.failure(error)
                        }
                    }
                }
                val capture = microphone.capture(onPcm = liveAudio?.let { queue -> { pcm ->
                    val offered = queue.trySend(pcm)
                    check(offered.isSuccess) { "English speech processing could not keep up. Nothing was sent; try a shorter message." }
                } }, onSpeech = { mutableTalk.update { it.copy(phase = "Hands-free: listening") } }) { pause ->
                    mutableTalk.update { if (it.recording) it.copy(phase = if (pause && finishAfterPause) "Pause detected—finishing" else "Listening") else it }
                }
                liveAudio?.close()
                diagnostics.captured(event, capture)
                mutableTalk.update { it.copy(recording = false, phase = "Transcribing on this phone") }
                service()
                if (discard || !capture.endpoint.speechDetected || capture.samples.size < 2400) {
                    diagnostics.discarded(event)
                    mutableTalk.update { it.copy(phase = if (microphone.held) "No speech captured—release to talk again" else "No usable speech captured") }
                    return@launch
                }
                val result = withContext(worker) {
                    if (engine.acceptsLivePcm) {
                        liveConsumer!!.await().getOrThrow()
                    } else {
                        engine.reset()
                        var offset = 0
                        while (offset < capture.samples.size) {
                            val end = minOf(offset + 1600, capture.samples.size)
                            engine.acceptPcm16(capture.samples.copyOfRange(offset, end)); offset = end
                        }
                    }
                    engine.finish()
                }
                diagnostics.transcribed(event, result, SystemClock.elapsedRealtimeNanos(), Process.getElapsedCpuTime() - cpu)
                val text = result.text.replace(Regex("(?i)[<\\[]\\s*(noise|silence|music|blank|unk|inaudible)\\s*[>\\]]"), "").trim()
                if (discard || (automatic && !handsFree.value.canSend(handsFreeGeneration!!, emergencyAudienceKey(false))) || !text.any { it.isLetterOrDigit() }) {
                    diagnostics.discarded(event)
                    mutableTalk.update { it.copy(phase = "No usable transcript—nothing sent") }; return@launch
                }
                transcribed = true
                if (forSos) {
                    mutableSosVoice.update { it.copy(text = text, completed = true) }
                    mutableTalk.update { it.copy(phase = if (microphone.held) "SOS captured—release your finger" else "SOS captured") }
                    return@launch // The SOS page owns confirmation; ordinary Auto-send never applies.
                }
                mutableTalk.update { it.copy(transcript = text) }
                when (ConversationPolicy.afterCapture(text, automatic || (settings.value.autoSend && !emergency), canSend)) {
                    ConversationPolicy.AfterCapture.EMPTY -> { diagnostics.discarded(event); mutableTalk.update { it.copy(phase = "No transcript produced") } }
                    ConversationPolicy.AfterCapture.SEND -> {
                        enqueueVoice(text, language, emergency, capture.endedNs / 1_000_000, event) {
                            !automatic || handsFree.value.canSend(handsFreeGeneration!!, emergencyAudienceKey(false))
                        }
                        val written = diagnostics.state.value.sent.any { it.id == event && (it.framedBytes ?: 0) > 0 }
                        mutableTalk.update { it.copy(draftSent = true, phase = if (microphone.held && !automatic) {
                            if (written) "Sent—release to talk again" else "Queued—release to talk again"
                        } else "Voice message queued for " + destination) }
                    }
                    ConversationPolicy.AfterCapture.REVIEW -> mutableTalk.update { it.copy(
                        phase = if (microphone.held) "Draft ready—release to talk again"
                            else if (canSend) "Review your words, then tap Send voice message"
                            else "Draft ready. Connect a phone, then send. Nothing has been played or sent.") }
                }
            } catch (error: Exception) {
                if (!transcribed) diagnostics.discarded(event)
                if (automatic) endHandsFree() // No automatic retry/send after a microphone or ASR failure.
                fail(error)
            }
            finally {
                withContext(NonCancellable) {
                    liveAudio?.cancel()
                    liveConsumer?.cancelAndJoin()
                    if (liveAudio != null) withContext(worker) { runCatching { recognizer?.reset() } }
                }
                captureInProgress = false
                if (automatic) microphone.release()
                captureIsHandsFree = false
                microphone.stop(); playbackActive = false
                mutableTalk.update { it.copy(busy = false, recording = false) }; service()
                audioLock.unlock(); wakePlayback.trySend(Unit)
            }
        }
        return true
    }
    fun releaseRecording() {
        microphone.release()
        mutableTalk.update { it.copy(awaitingRelease = false,
            phase = if (!it.busy && it.awaitingRelease) {
                if (captureForSos) "SOS capture finished" else if (it.draftSent) "Voice message queued or sent" else if (it.transcript.isNotBlank()) "Draft ready to review" else "Ready to talk"
            } else it.phase) }
    }
    fun releaseEmergencyRecording() { if (captureForSos) releaseRecording() }
    fun cancelEmergencyRecording() { if (captureForSos) cancelRecording() }
    fun cancelRecording() { if (captureInProgress) stop(); releaseRecording() }
    private fun interruptAudio() { discard = true; microphone.stop(); synthesizer.stop(); player.stop(); alarm.stop() }
    fun stop() {
        // Ordinary controls cannot cut off an incoming emergency. Acknowledge or
        // the explicit end-session action remains available.
        if (activeMessage?.let { it.emergency && it.direction == "IN" && !PublicRelaySession.owns(it) } == true) return
        if (handsFree.value.active) muteHandsFree(true)
        interruptAudio()
    }
    fun sendText() {
        val current = talk.value
        if (current.busy || current.draftSent || current.transcript.isBlank()) return
        if (current.emergency) { reportError("Open Emergency SOS to review and confirm this alert."); return }
        mutableTalk.update { it.copy(busy = true) }
        scope.launch {
            try {
                draftMeasurementId = diagnostics.forDraft(draftMeasurementId, current.language.code)
                enqueueVoice(current.transcript, current.language, current.emergency, measurementId = draftMeasurementId)
                mutableTalk.update { it.copy(draftSent = true, phase = "Voice message queued for " + destination) }
            } catch (error: Exception) { fail(error) }
            finally { mutableTalk.update { it.copy(busy = false) }; wakePlayback.trySend(Unit) }
        }
    }
    fun emergencyAudienceKey(): String {
        if (!canSend) return ""
        return mode.value.name + ":" + if (mode.value.isLan) {
            val room = lan.state.value
            room.roomId + ":" + room.hostId + ":" + room.members.filter { it.online && it.id != room.selfId }.map { it.id }.sorted().joinToString(",")
        } else radio.state.value.peerId + ":" + radio.state.value.code
    }
    fun emergencyAudienceKey(bleRoute: Boolean, publicRoute: Boolean = false) =
        if (publicRoute) publicRelay.audienceKey() else if (bleRoute) relay.audienceKey() else emergencyAudienceKey()
    fun startRelay() {
        if (publicRelay.state.value.active) { reportError("Stop public BLE SOS before starting a private team relay."); return }
        ble.stop(); relay.start()
    }
    fun startPublicRelay() {
        if (!activityVisible) { reportError("Open Emergency SOS to turn on public receiving."); return }
        if (relay.state.value.active || relay.state.value.starting) { reportError("Stop the private team relay before starting public BLE SOS."); return }
        foreground.allowRetry(); ble.stop(); publicRelay.start()
    }
    private fun clearPublicPlayback() {
        if (activeMessage?.let(PublicRelaySession::owns) == true) interruptAudio()
        store.messages.value.filter(PublicRelaySession::owns).forEach {
            emergencyRetryJobs.remove(it.key)?.cancel(); emergencyRetries.remove(it.key)
            manualPlayback.remove(it.key); SpeechService.cancelAlert(application, it.key)
        }
    }
    fun stopPublicRelay() { clearPublicPlayback(); publicRelay.stop() }
    fun stopAllConnections() { endHandsFree(); interruptAudio(); relay.stop(); stopPublicRelay(); disconnect() }
    fun saveEmergencyDraft(text: String, language: LanguageCode, captureId: Long?) {
        if (talk.value.busy) return
        val previous = sosVoice.value
        val sameCapture = captureId != null && captureId == previous.captureId && !previous.sent &&
            text == previous.text && language == previous.language
        if (!sameCapture && !previous.sent) previous.captureId?.let(diagnostics::discarded)
        mutableSosVoice.value = SosVoiceState(if (sameCapture) captureId else null, language, text.take(8000), completed = sameCapture)
    }
    fun sendEmergency(draft: SosDraft, onFinished: (Boolean) -> Unit) {
        scope.launch {
            try {
                val bleRoute = draft.audience.startsWith("ble:")
                val publicRoute = draft.audience.startsWith(PublicRelaySession.PREFIX)
                check(draft.audience.isNotBlank() && emergencyAudienceKey(bleRoute, publicRoute) == draft.audience) { "The intended recipients changed. Review and confirm the emergency again." }
                val text = UnicodeText.normalize(draft.text)
                require(text.any { it.isLetterOrDigit() }) { "Enter an emergency message first." }
                val voice = sosVoice.value
                val event = if (draft.captureId != null && draft.captureId == voice.captureId && voice.completed && !voice.sent &&
                    text == UnicodeText.normalize(voice.text) && draft.language == voice.language) voice.captureId else null
                val keys = if (publicRoute) listOf(publicRelay.enqueue(text, draft.language, event))
                    else if (bleRoute) listOf(relay.enqueue(text, draft.language, event))
                    else enqueueVoice(text, draft.language, true, measurementId = event)
                if (event == null && !voice.sent) voice.captureId?.let(diagnostics::discarded)
                mutableSosVoice.value = SosVoiceState(event, draft.language, text, completed = event != null, sent = true, messageKeys = keys)
                onFinished(true)
            } catch (error: Exception) { fail(error); onFinished(false) }
        }
    }
    fun sendChat(text: String, recipient: String = "", onFinished: (Boolean) -> Unit) {
        scope.launch {
            try {
                if (mode.value.isLan) lan.enqueue(text, talk.value.language, false, chat = true, recipient = recipient)
                else { require(recipient.isBlank()); radio.enqueue(text, talk.value.language, false, chat = true) }
                onFinished(true)
            }
            catch (error: Exception) { fail(error); onFinished(false) }
        }
    }
    fun setAutoPlay(enabled: Boolean) {
        if (!enabled && activeMessage != null && activeMessage?.emergency != true && !handsFree.value.active) stop()
        saveSettings(settings.value.copy(autoSpeak = enabled))
    }
    fun speak(text: String, language: LanguageCode, messageKey: String? = null) {
        if (talk.value.busy || text.isBlank() || !audioLock.tryLock()) return
        if (activityVisible) foreground.allowRetry()
        mutablePlayingKey.value = messageKey
        discard = false; mutableTalk.update { it.copy(busy = true, error = null) }
        scope.launch {
            try { speakInternal(text, language, false); mutableTalk.update { it.copy(phase = if (discard) "Playback stopped" else "Local playback complete") } }
            catch (error: Exception) { fail(error) }
            finally {
                resumeCaptureAfterMs = SystemClock.elapsedRealtime() + 250
                mutablePlayingKey.value = null; playbackActive = false; mutableTalk.update { it.copy(busy = false) }; service(); audioLock.unlock(); wakePlayback.trySend(Unit)
            }
        }
    }
    private suspend fun speakInternal(text: String, language: LanguageCode, emergency: Boolean, measurementId: Long? = null): PlaybackTiming? {
        playbackActive = true
        requireForegroundService()
        var first: Long? = null
        var last: PlaybackTiming? = null
        for (part in UnicodeText.chunks(text)) {
            if (discard) return null
            mutableTalk.update { it.copy(phase = "Speaking ${language.displayName}${if (emergency) " alert" else ""}") }
            val requestedNs = SystemClock.elapsedRealtimeNanos()
            val audio = withContext(worker) { synthesizer.synthesize(TtsRequest(part, language, emergency, if (emergency) minOf(settings.value.rate, 150) else settings.value.rate)) }
            diagnostics.synthesized(measurementId, audio, requestedNs)
            if (discard) return null
            val timing = player.play(audio, emergency, settings.value.emergencyBoost,
                onSubmitted = { diagnostics.submitted(measurementId, it) },
                onVolumeRestricted = { diagnostics.put("emergencyVolumeBoostRestricted", true) }) { discard }
            if (first == null) first = timing.submittedNs
            last = timing
            diagnostics.playedChunk(measurementId, timing.submittedNs, timing.completedNs)
            if (!timing.completed) return null
        }
        return last?.copy(submittedNs = first ?: last.submittedNs)
    }
    private suspend fun playbackLoop() {
        for (signal in wakePlayback) {
            while (true) {
                val message = nextPlayback() ?: break
                if (talk.value.busy || !audioLock.tryLock()) break
                discard = false; activeMessage = message
                mutablePlayingKey.value = message.key
                val replaying = manualPlayback.remove(message.key)
                val measurementId = diagnostics.beginPlayback(message.key)
                mutableTalk.update { it.copy(busy = true) }
                var played = false
                try {
                    store.mutate(message.key) { it.playback = "PLAYING" }
                    val repeats = if (message.emergency && message.direction == "IN" && !PublicRelaySession.owns(message)) settings.value.emergencyRepeats else 1
                    // Reasons not to read this aloud: already stopped, already
                    // acknowledged by a person, or a public alert that is no longer
                    // playable. The alarm shares this test so it can never sound for
                    // a message that is then skipped — an alarm with no words is
                    // just noise, and it would train people to ignore the next one.
                    suspend fun suppressed() = discard || (!replaying &&
                        (store.find(message.key)?.humanAcknowledged == true ||
                            (PublicRelaySession.owns(message) && (!publicRelay.state.value.active ||
                                message.key !in publicRelay.playableKeys))))
                    // Vibrate and sound on the alarm stream before the first
                    // reading, so an alert is noticed from a pocket with the
                    // screen off. Stops before speech so the two never overlap.
                    // Received emergencies only: never your own sent alert, never a
                    // manual replay, never an ordinary voice or text message.
                    if (message.emergency && message.direction == "IN" && !replaying && !suppressed()) {
                        mutableTalk.update { it.copy(phase = "Emergency alert") }
                        runCatching { alarm.announce() }
                    }
                    repeat(repeats) { iteration ->
                        if (suppressed()) return@repeat
                        val timing = speakInternal(message.text, LanguageCode.fromCode(message.language), message.emergency && message.direction == "IN", measurementId)
                        if (timing != null && timing.completed) {
                            played = true
                            val receipt = if (message.receiptElapsedMs in 1..(timing.submittedNs / 1_000_000)) timing.submittedNs / 1_000_000 - message.receiptElapsedMs else -1
                            if (message.direction == "OUT") store.mutate(message.key) { it.playback = "PLAYED" }
                            else if (PublicRelaySession.owns(message)) publicRelay.played(message)
                            else if (RelaySession.owns(message)) relay.played(message)
                            else if (message.roomId.isNotBlank()) lan.played(message, receipt, (timing.completedNs - timing.submittedNs) / 1_000_000)
                            else radio.played(message, receipt, (timing.completedNs - timing.submittedNs) / 1_000_000)
                        }
                        if (iteration + 1 < repeats && !discard) delay(1500)
                    }
                    if (!played) store.mutate(message.key) { it.playback = "INTERRUPTED" }
                    diagnostics.playbackFinished(measurementId, if (discard) "INTERRUPTED" else if (played) "PLAYED" else "INTERRUPTED")
                    mutableTalk.update { it.copy(phase = if (played) "Message playback complete" else "Playback stopped - replay from Talk") }
                } catch (error: AudioFocusUnavailable) {
                    if (played) {
                        diagnostics.playbackFinished(measurementId, "PLAYED")
                    } else if (message.emergency && message.direction == "IN" && !discard && scheduleEmergencyRetry(message)) {
                        diagnostics.playbackFinished(measurementId, "WAITING_AUDIO")
                        mutableTalk.update { it.copy(phase = "Emergency waiting for Android audio access") }
                    } else {
                        diagnostics.playbackFinished(measurementId, "INTERRUPTED")
                        store.mutate(message.key) { it.playback = "INTERRUPTED" }
                        fail(IllegalStateException("Audio was interrupted. The message was not marked played; replay it when audio is available."))
                    }
                } catch (error: Exception) { diagnostics.playbackFinished(measurementId, "FAILED"); store.mutate(message.key) { it.playback = "FAILED" }; fail(error) }
                finally {
                    if (played) emergencyRetries.remove(message.key)
                    resumeCaptureAfterMs = SystemClock.elapsedRealtime() + 250
                    activeMessage = null; mutablePlayingKey.value = null; playbackActive = false; mutableTalk.update { it.copy(busy = false) }
                    service(); audioLock.unlock()
                }
            }
            scheduleHandsFree()
        }
    }
    private fun nextPlayback() = PlaybackQueue.next(store.messages.value, manualPlayback,
        settings.value.autoSpeak || handsFree.value.active, mode.value.isLan, lan.state.value.roomId, relay.state.value.active,
        publicRelay.state.value.active && !publicRelay.state.value.starting, publicRelay.playableKeys)
    private suspend fun scheduleEmergencyRetry(message: MessageEntity): Boolean {
        if (store.find(message.key)?.humanAcknowledged != false) return false
        val attempt = emergencyRetries[message.key] ?: 0
        val waitMs = EmergencyAudioPolicy.retryDelayMs(attempt) ?: return false
        emergencyRetries[message.key] = attempt + 1
        store.mutate(message.key) { it.playback = "WAITING_AUDIO" }
        emergencyRetryJobs[message.key] = scope.launch {
            try {
                delay(waitMs)
                store.mutate(message.key) { if (!it.humanAcknowledged && it.playback == "WAITING_AUDIO") it.playback = "PENDING" }
            } catch (error: Exception) { if (error !is CancellationException) fail(error) }
            finally {
                if (emergencyRetryJobs[message.key] === currentCoroutineContext().job) emergencyRetryJobs.remove(message.key)
                wakePlayback.trySend(Unit)
            }
        }
        return true
    }
    fun replay(message: MessageEntity) {
        if (message.channel != "VOICE") return
        if (message.key == activeMessage?.key) return
        if (activityVisible) foreground.allowRetry()
        if (captureIsHandsFree && captureInProgress) interruptAudio()
        emergencyRetryJobs.remove(message.key)?.cancel(); emergencyRetries.remove(message.key)
        scope.launch { manualPlayback.add(message.key); store.mutate(message.key) { it.playback = "PENDING" }; wakePlayback.trySend(Unit) }
    }
    fun acknowledge(key: String) {
        emergencyRetryJobs.remove(key)?.cancel(); emergencyRetries.remove(key)
        if (activeMessage?.key == key) interruptAudio()
        if (mutableEmergency.value?.key == key) mutableEmergency.value = null
        scope.launch { try {
            val message = store.find(key)
            if (message != null && PublicRelaySession.owns(message)) publicRelay.acknowledge(message)
            else if (message != null && RelaySession.owns(message)) relay.acknowledge(message)
            else if (message?.roomId?.isNotBlank() == true) lan.acknowledge(key) else radio.acknowledge(key)
            SpeechService.cancelAlert(application, key)
        } catch (error: Exception) { fail(error) } }
    }
    fun retry(key: String) { scope.launch { try {
        if (store.find(key)?.roomId?.isNotBlank() == true) lan.retry(key) else radio.retry(key)
    } catch (error: Exception) { fail(error) } } }
    fun saveSettings(value: LocalSettings) { scope.launch { try {
        preferences.save(value); wakePlayback.trySend(Unit)
    } catch (error: Exception) { fail(error) } } }
    fun clearHistory() { scope.launch { endHandsFree(); interruptAudio(); stopPublicRelay(); relay.stop(); relay.clearPending(); disconnectNow(); store.clear() } }
    fun export(uri: Uri) {
        scope.launch(Dispatchers.IO) {
            try { application.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(diagnostics.json()) } ?: error("Cannot write export") }
            catch (error: Exception) { fail(error) }
        }
    }
}
