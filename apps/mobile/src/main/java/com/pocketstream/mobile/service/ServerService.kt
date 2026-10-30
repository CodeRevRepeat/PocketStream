package com.pocketstream.mobile.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.pocketstream.mobile.MainActivity
import com.pocketstream.mobile.server.NetworkUtils
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import com.pocketstream.mobile.server.PocketHttpServer

class ServerService : Service() {

    companion object {
        private const val TAG = "ServerService"
        private const val CHANNEL_ID = "pocketstream_server_channel"
        private const val NOTIFICATION_ID = 1001

        const val ACTION_START = "com.pocketstream.mobile.action.START"
        const val ACTION_STOP = "com.pocketstream.mobile.action.STOP"

        var isServerRunning = false
            private set
        var serverUrl: String = ""
            private set
        var serviceInstance: ServerService? = null
            private set

        fun updateNotification(title: String, message: String) {
            serviceInstance?.updateNotificationText(title, message)
        }

        fun start(context: Context) {
            val intent = Intent(context, ServerService::class.java).apply {
                action = ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, ServerService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }

        private val disconnectClient by lazy {
            okhttp3.OkHttpClient.Builder()
                .connectTimeout(1, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(1, java.util.concurrent.TimeUnit.SECONDS)
                .build()
        }
    }

    private var httpServer: PocketHttpServer? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        serviceInstance = this
        createNotificationChannel()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                buildNotification("PocketStream service initializing..."),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            )
        } else {
            startForeground(
                NOTIFICATION_ID,
                buildNotification("PocketStream service initializing...")
            )
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopServer()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START, null -> {
                startServer()
            }
        }
        return START_STICKY
    }

    private fun startServer() {
        if (isServerRunning) return

        try {
            val port = 8080
            val ip = NetworkUtils.getLocalWifiIpAddress()
            serverUrl = "http://$ip:$port"

            httpServer = PocketHttpServer(applicationContext, port)
            httpServer?.start()

            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PocketStream:ServerWakeLock")
            wakeLock?.acquire(6 * 60 * 60 * 1000L /* 6 hours persistent streaming & transcode */)

            isServerRunning = true
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    buildNotification("Streaming active at $serverUrl"),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                )
            } else {
                startForeground(NOTIFICATION_ID, buildNotification("Streaming active at $serverUrl"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed starting HTTP server: ${e.message}", e)
            stopSelf()
        }
    }

    private fun stopServer() {
        try {
            notifyTvDisconnected()
            com.pocketstream.mobile.transcoder.TranscoderManager.purgeAllCache(applicationContext)
            httpServer?.stop()
            httpServer = null
            wakeLock?.let {
                if (it.isHeld) it.release()
            }
            wakeLock = null
            isServerRunning = false
            serverUrl = ""
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping server: ${e.message}")
        }
    }

    private fun notifyTvDisconnected() {
        try {
            val prefs = getSharedPreferences("pocketstream_mobile_prefs", Context.MODE_PRIVATE)
            val tvIp = prefs.getString("last_tv_ip", "") ?: ""
            val tvPort = prefs.getInt("last_tv_port", 8085)
            if (tvIp.isNotEmpty()) {
                Thread {
                    try {
                        val body = "{}".toRequestBody("application/json".toMediaType())
                        val req = okhttp3.Request.Builder()
                            .url("http://$tvIp:$tvPort/disconnect")
                            .post(body)
                            .build()
                        disconnectClient.newCall(req).execute().close()
                    } catch (_: Exception) {
                    }
                }.start()
            }
        } catch (_: Exception) {}
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "PocketStream Server",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps PocketStream media streaming active over Wi-Fi"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun openAppPendingIntent(): PendingIntent {
        val openAppIntent = Intent(this, MainActivity::class.java)
        return PendingIntent.getActivity(
            this, 0, openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun buildNotification(text: String): Notification {
        val stopIntent = Intent(this, ServerService::class.java).apply {
            action = ACTION_STOP
        }
        val pendingStop = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("PocketStream Server Running")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(openAppPendingIntent())
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", pendingStop)
            .setOngoing(true)
            .build()
    }

    fun updateNotificationText(title: String, message: String) {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val notif = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(message)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(openAppPendingIntent())
            .setOngoing(true)
            .build()
        manager.notify(NOTIFICATION_ID, notif)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopServer()
        if (serviceInstance === this) serviceInstance = null
        super.onDestroy()
    }
}