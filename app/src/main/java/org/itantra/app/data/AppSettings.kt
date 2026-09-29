package org.itantra.app.data

import android.content.Context
import android.os.Build
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.UUID

private val Context.settingsStore by preferencesDataStore("itantra-settings")
data class LocalSettings(
    val deviceId: String = "", val name: String = Build.MODEL,
    val autoSpeak: Boolean = true, val rate: Int = 165,
    val emergencyBoost: Boolean = false, val emergencyRepeats: Int = 2,
    val autoSend: Boolean = false, val finishAfterPause: Boolean = true,
    val connectionGroup: Boolean = false, val connectionMethod: String = "WIFI_DIRECT",
    val theme: String = "SYSTEM", val haptics: Boolean = true, val reducedMotion: Boolean = false,
    val spokenGuidance: Boolean = false, val wifiOnlyDownloads: Boolean = true,
    val handsFreeMode: Boolean = false, val handsFreePauseMs: Int = 700
)
class AppSettings(private val context: Context) {
    private val id = stringPreferencesKey("device_id")
    private val name = stringPreferencesKey("device_name")
    private val autoSpeak = booleanPreferencesKey("auto_speak")
    private val rate = intPreferencesKey("speech_rate")
    private val boost = booleanPreferencesKey("emergency_volume_authorized")
    private val repeats = intPreferencesKey("emergency_repeats")
    private val autoSend = booleanPreferencesKey("auto_send_voice")
    private val finishAfterPause = booleanPreferencesKey("finish_after_pause")
    private val connectionGroup = booleanPreferencesKey("connection_group")
    private val connectionMethod = stringPreferencesKey("connection_method")
    private val theme = stringPreferencesKey("theme")
    private val haptics = booleanPreferencesKey("haptics")
    private val reducedMotion = booleanPreferencesKey("reduced_motion")
    private val spokenGuidance = booleanPreferencesKey("spoken_guidance")
    private val wifiOnlyDownloads = booleanPreferencesKey("wifi_only_downloads")
    private val handsFreeMode = booleanPreferencesKey("hands_free_mode")
    private val handsFreePauseMs = intPreferencesKey("hands_free_pause_ms")
    val flow = context.settingsStore.data.map { p -> LocalSettings(
        p[id].orEmpty(), p[name] ?: Build.MODEL, p[autoSpeak] ?: true,
        (p[rate] ?: 165).coerceIn(100, 230), p[boost] ?: false,
        (p[repeats] ?: 2).coerceIn(1, 3), p[autoSend] ?: false, p[finishAfterPause] ?: true,
        p[connectionGroup] ?: false, p[connectionMethod] ?: "WIFI_DIRECT",
        p[theme]?.takeIf { it in listOf("SYSTEM", "LIGHT", "DARK") } ?: "SYSTEM",
        p[haptics] ?: true, p[reducedMotion] ?: false, p[spokenGuidance] ?: false, p[wifiOnlyDownloads] ?: true,
        p[handsFreeMode] ?: false, (p[handsFreePauseMs] ?: 700).coerceIn(500, 1200))
    }
    suspend fun initialize(): LocalSettings {
        context.settingsStore.edit { if (it[id] == null) it[id] = UUID.randomUUID().toString() }
        return flow.first()
    }
    suspend fun save(value: LocalSettings) {
        context.settingsStore.edit {
            it[name] = value.name.trim().take(40).ifBlank { Build.MODEL }
            it[autoSpeak] = value.autoSpeak; it[rate] = value.rate.coerceIn(100, 230)
            it[boost] = value.emergencyBoost; it[repeats] = value.emergencyRepeats.coerceIn(1, 3)
            it[autoSend] = value.autoSend
            it[finishAfterPause] = value.finishAfterPause
            it[connectionGroup] = value.connectionGroup; it[connectionMethod] = value.connectionMethod
            it[theme] = value.theme; it[haptics] = value.haptics; it[reducedMotion] = value.reducedMotion
            it[spokenGuidance] = value.spokenGuidance; it[wifiOnlyDownloads] = value.wifiOnlyDownloads
            it[handsFreeMode] = value.handsFreeMode; it[handsFreePauseMs] = value.handsFreePauseMs.coerceIn(500, 1200)
        }
    }
}
