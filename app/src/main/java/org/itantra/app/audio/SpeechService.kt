package org.itantra.app.audio

import android.Manifest
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import org.itantra.app.ItantraApplication
import org.itantra.app.MainActivity
import org.itantra.app.data.MessageEntity

class SpeechService : Service() {
    private var instanceId = 0L
    override fun onCreate() {
        super.onCreate()
        instanceId = (application as ItantraApplication).runtime.foregroundServiceCreated()
    }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() {
        (application as ItantraApplication).runtime.foregroundServiceStopped(instanceId)
        super.onDestroy()
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val runtime = (application as ItantraApplication).runtime
        if (intent?.action == "DISCONNECT") { runtime.stopAllConnections(); return START_NOT_STICKY }
        if (intent?.action == "STOP_PUBLIC_RELAY") { runtime.stopPublicRelay(); runtime.refreshForegroundService(); return START_NOT_STICKY }
        if (intent?.action == "ACK") {
            intent.getStringExtra("key")?.let(runtime::acknowledge)
            runtime.refreshForegroundService()
            return START_NOT_STICKY
        }
        if (intent?.action == "MUTE") { runtime.muteHandsFree(true); return START_NOT_STICKY }
        if (intent?.action == "END_HANDS_FREE") { runtime.endHandsFree(); return START_NOT_STICKY }
        val leaseId = intent?.getLongExtra("leaseId", -1L) ?: -1L
        // Intent flags can be stale by the time Android dispatches this callback.
        val demand = runtime.foregroundServiceDemand(instanceId, leaseId)
        if (demand == null) {
            stopSelfResult(startId) // Does not stop a newer start already queued by Android.
            return START_NOT_STICKY
        }
        try {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("communication", "Offline communication", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val disconnect = PendingIntent.getService(this, 1, Intent(this, SpeechService::class.java).setAction("DISCONNECT"), PendingIntent.FLAG_IMMUTABLE)
        val handsFree = demand.handsFree
        val notification = Notification.Builder(this, "communication").setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("iTantra · offline communication").setContentText(when {
                handsFree && demand.muted -> "Hands-free muted · replies can still play"
                handsFree -> "Hands-free active · speech sends automatically"
                demand.recording -> "Microphone active"
                demand.captureWork -> "Processing speech on this phone"
                runtime.publicRelay.state.value.active -> "Public BLE SOS · receiving and automatic relay on"
                else -> "Local link / speech active"
            })
            .setContentIntent(open).setOngoing(true).addAction(Notification.Action.Builder(null, "Disconnect", disconnect).build())
        if (runtime.publicRelay.state.value.active) {
            val stopRelay = PendingIntent.getService(this, 5, Intent(this, SpeechService::class.java).setAction("STOP_PUBLIC_RELAY"), PendingIntent.FLAG_IMMUTABLE)
            notification.addAction(Notification.Action.Builder(null, "Stop BLE SOS", stopRelay).build())
        }
        if (handsFree) {
            val mute = PendingIntent.getService(this, 3, Intent(this, SpeechService::class.java).setAction("MUTE"), PendingIntent.FLAG_IMMUTABLE)
            val end = PendingIntent.getService(this, 4, Intent(this, SpeechService::class.java).setAction("END_HANDS_FREE"), PendingIntent.FLAG_IMMUTABLE)
            notification.addAction(Notification.Action.Builder(null, "Mute", mute).build())
            notification.addAction(Notification.Action.Builder(null, "End hands-free", end).build())
        }
        if (Build.VERSION.SDK_INT >= 30) {
            var types = 0
            if (demand.microphone) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            if (demand.playback) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            if (demand.connected) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            startForeground(1, notification.build(), types)
        } else startForeground(1, notification.build())
        runtime.foregroundServicePromoted(instanceId, leaseId)
        }
        catch (error: RuntimeException) {
            // The OS can revoke permissions between UI checks and this callback.
            runtime.foregroundServiceFailed(error, instanceId, leaseId)
            stopSelf()
        }
        return START_NOT_STICKY
    }
    companion object {
        fun alert(context: Context, message: MessageEntity) {
            if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
            val manager = context.getSystemService(NotificationManager::class.java)
            // Vibrate and bypass Do Not Disturb where the user has allowed it, so
            // an alert is noticed with the screen off. The channel stays silent
            // because the tone and the spoken words are played by the app itself;
            // a channel sound would talk over them.
            manager.createNotificationChannel(
                NotificationChannel("emergency", "Incoming emergency messages", NotificationManager.IMPORTANCE_HIGH).apply {
                    setSound(null, null)
                    enableVibration(true)
                    vibrationPattern = longArrayOf(0, 500, 250, 500, 250, 500)
                    enableLights(true)
                    setBypassDnd(true)
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                }
            )
            val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val acknowledge = PendingIntent.getService(context, message.key.hashCode(), Intent(context, SpeechService::class.java).setAction("ACK").putExtra("key", message.key), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val alert = Notification.Builder(context, "emergency").setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle("Emergency · ${message.peerName}").setContentText(message.text)
                .setStyle(Notification.BigTextStyle().bigText(message.text)).setContentIntent(open)
                .setVisibility(Notification.VISIBILITY_PUBLIC).setAutoCancel(false)
                // CATEGORY_ALARM plus a full-screen intent asks Android to turn the
                // screen on and show this over the lock screen. The OS still decides;
                // it is a request, not a guarantee.
                .setCategory(Notification.CATEGORY_ALARM)
                .setFullScreenIntent(open, true)
                .addAction(Notification.Action.Builder(null, "Acknowledge", acknowledge).build()).build()
            manager.notify(message.key, 2, alert)
        }
        fun cancelAlert(context: Context, key: String) { context.getSystemService(NotificationManager::class.java).cancel(key, 2) }
    }
}
