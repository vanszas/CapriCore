package com.capri.mobile.remote

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.capri.mobile.MainActivity
import com.capri.mobile.R
import com.capri.mobile.tr
import kotlin.concurrent.thread

/**
 * Foreground task monitor for paired PC agents.
 * Uses notification ID 4209 (isolated from CapriService ID 7 and DownloadService ID 8).
 */
class RemoteMonitorService : Service() {
    companion object {
        const val NOTIFICATION_ID = 4209
        private const val CHANNEL = "capri_remote_tasks"
        const val ACTION_START = "com.capri.mobile.remote.MONITOR_START"
        const val ACTION_STOP = "com.capri.mobile.remote.MONITOR_STOP"
        const val ACTION_STOP_TASK = "com.capri.mobile.remote.STOP_TASK"

        @Volatile
        var running = false
            private set

        @Volatile
        private var activePlayer: MediaPlayer? = null

        fun playTone(context: Context, uriString: String) {
            if (uriString.isBlank()) return
            val appCtx = context.applicationContext
            val parsed = runCatching { Uri.parse(uriString) }.getOrNull() ?: return
            val playedByMedia = runCatching {
                activePlayer?.let { prev ->
                    runCatching { prev.stop() }
                    runCatching { prev.release() }
                    activePlayer = null
                }
                val mp = MediaPlayer()
                activePlayer = mp
                mp.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                if (parsed.scheme == "file" && !parsed.path.isNullOrBlank()) {
                    mp.setDataSource(parsed.path)
                } else {
                    mp.setDataSource(appCtx, parsed)
                }
                mp.setOnCompletionListener { player ->
                    if (activePlayer === player) activePlayer = null
                    runCatching { player.release() }
                }
                mp.setOnErrorListener { player, _, _ ->
                    if (activePlayer === player) activePlayer = null
                    runCatching { player.release() }
                    true
                }
                mp.prepare()
                mp.start()
                true
            }.getOrDefault(false)
            if (!playedByMedia) {
                runCatching {
                    val rt = RingtoneManager.getRingtone(appCtx, parsed)
                    rt?.play()
                }
            }
        }

        fun sync(ctx: Context) {
            val prefs = RemotePrefs(ctx)
            val profile = prefs.activeProfile()
            val token = prefs.activeToken()
            if (prefs.backgroundMonitor && profile != null && token.isNotBlank()) {
                val i = Intent(ctx, RemoteMonitorService::class.java).setAction(ACTION_START)
                if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
            } else if (running) {
                ctx.startService(Intent(ctx, RemoteMonitorService::class.java).setAction(ACTION_STOP))
            }
        }
    }

    @Volatile
    private var stopLoop = false
    private var worker: Thread? = null
    private val lastStatuses = HashMap<String, String>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopLoop = true
                running = false
                RemotePrefs(this).backgroundMonitor = false
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_STOP_TASK -> {
                val sessionId = intent.getStringExtra("session_id").orEmpty()
                if (sessionId.isNotBlank()) {
                    thread(name = "capri-remote-stop-task") {
                        runCatching {
                            val prefs = RemotePrefs(this)
                            val p = prefs.activeProfile() ?: return@runCatching
                            RemoteClient(p.activeEndpoint(), prefs.activeToken(), prefs.deviceId)
                                .stopSession(sessionId)
                        }
                    }
                }
                return START_STICKY
            }
        }
        running = true
        startForegroundNotification(tr("PC monitor connected"), tr("Watching background agent tasks"))
        if (worker?.isAlive != true) {
            stopLoop = false
            worker = thread(name = "capri-remote-monitor") { pollLoop() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopLoop = true
        running = false
        super.onDestroy()
    }

    private fun pollLoop() {
        while (!stopLoop) {
            runCatching {
                val prefs = RemotePrefs(this)
                val profile = prefs.activeProfile()
                val token = prefs.activeToken()
                if (!prefs.backgroundMonitor || profile == null || token.isBlank()) {
                    stopLoop = true
                    return@runCatching
                }
                val client = RemoteClient(profile.activeEndpoint(), token, prefs.deviceId)
                val tasks = client.tasks()
                val active = tasks.filter { it.status == "running" || it.status == "queued" || it.status == "waiting" }
                var playCompleteSound = false
                for (t in tasks) {
                    val prev = lastStatuses[t.id]
                    if (prev != null && prev in setOf("running", "queued", "waiting") &&
                        t.status in setOf("completed", "failed")
                    ) {
                        playCompleteSound = true
                    }
                    lastStatuses[t.id] = t.status
                }
                if (playCompleteSound) playNotificationTone(prefs.notificationSoundUri)
                if (active.isNotEmpty()) {
                    val first = active.first()
                    val title = if (active.size == 1) first.title else "${active.size} active PC tasks"
                    val detail = first.detail.ifBlank { "${first.phase} · ${first.agents.joinToString()}" }
                    updateNotification(title, detail, first.sessionId)
                } else {
                    updateNotification("${profile.name} · Idle", tr("No active PC tasks"), null)
                }
            }
            try {
                Thread.sleep(4000L)
            } catch (_: InterruptedException) {
                break
            }
        }
        running = false
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun playNotificationTone(uriString: String) {
        playTone(applicationContext, uriString)
    }

    private fun buildNotification(title: String, body: String, stopSessionId: String?): android.app.Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, tr("PC task monitor"), NotificationManager.IMPORTANCE_LOW),
            )
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val openIntent = Intent(this, MainActivity::class.java).apply {
            this.flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("tab", "remote")
        }
        val openPi = PendingIntent.getActivity(this, 41, openIntent, flags)
        val stopMonPi = PendingIntent.getService(
            this,
            42,
            Intent(this, RemoteMonitorService::class.java).setAction(ACTION_STOP),
            flags,
        )
        val builder = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_capri_stat)
            .setContentTitle(title)
            .setContentText(body)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openPi)
        if (!stopSessionId.isNullOrBlank()) {
            val stopTaskPi = PendingIntent.getService(
                this,
                43,
                Intent(this, RemoteMonitorService::class.java)
                    .setAction(ACTION_STOP_TASK)
                    .putExtra("session_id", stopSessionId),
                flags,
            )
            builder.addAction(0, tr("Stop task"), stopTaskPi)
        }
        builder.addAction(0, tr("Stop monitor"), stopMonPi)
        return builder.build()
    }

    private fun startForegroundNotification(title: String, body: String) {
        val n = buildNotification(title, body, null)
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            n,
            if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0,
        )
    }

    private fun updateNotification(title: String, body: String, stopSessionId: String?) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, buildNotification(title, body, stopSessionId))
    }
}
