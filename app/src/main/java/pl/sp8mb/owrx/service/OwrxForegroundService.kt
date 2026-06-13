package pl.sp8mb.owrx.service

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import pl.sp8mb.owrx.OwrxApp
import pl.sp8mb.owrx.R
import pl.sp8mb.owrx.session.OwrxSession
import pl.sp8mb.owrx.ui.MainActivity
import javax.inject.Inject

@AndroidEntryPoint
class OwrxForegroundService : LifecycleService() {

    @Inject
    lateinit var session: OwrxSession

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_STOP -> {
                session.disconnect()   // zamknij WebSocket + audio (inaczej sesja żyje dalej)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            else -> start()
        }
        return START_STICKY
    }

    private fun start() {
        val notification = buildNotification(getString(R.string.notification_disconnected))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        acquireLocks()
        registerNetworkCallback()
        observeSession()
    }

    private fun observeSession() {
        lifecycleScope.launch {
            session.connectionState.collect { state ->
                val text = when (state) {
                    is OwrxSession.ConnectionState.Disconnected -> getString(R.string.notification_disconnected)
                    is OwrxSession.ConnectionState.Connecting -> "Łączenie…"
                    is OwrxSession.ConnectionState.Connected -> describeTuning()
                    is OwrxSession.ConnectionState.Reconnecting -> "Ponawianie połączenia (#${state.attempt})"
                }
                updateNotification(text)
            }
        }
    }

    private fun describeTuning(): String {
        val cfg = session.radioConfig.value
        val desired = session.desired.value
        val center = cfg.centerFreq
        val offset = desired.offsetFreq ?: cfg.startOffsetFreq
        val mod = desired.mod ?: cfg.startMod
        return if (center != null && offset != null) {
            "%.4f MHz %s".format((center + offset) / 1e6, (mod ?: "").uppercase())
        } else {
            "Połączony"
        }
    }

    private fun registerNetworkCallback() {
        if (networkCallback != null) return
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                session.kickReconnect()
            }
        }.also {
            cm.registerNetworkCallback(
                NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build(),
                it
            )
        }
    }

    fun updateNotification(text: String) {
        NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun buildNotification(text: String): Notification {
        val contentIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, OwrxForegroundService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, OwrxApp.CHANNEL_PLAYBACK)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setContentIntent(contentIntent)
            .addAction(0, getString(R.string.action_stop), stopIntent)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun acquireLocks() {
        if (wakeLock == null) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Owrx::ServiceWakeLock")
                .apply { acquire() }
        }
        if (wifiLock == null) {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                WifiManager.WIFI_MODE_FULL_LOW_LATENCY else WifiManager.WIFI_MODE_FULL_HIGH_PERF
            wifiLock = wm.createWifiLock(mode, "Owrx::WifiLock").apply { acquire() }
        }
    }

    override fun onDestroy() {
        networkCallback?.let {
            (getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).unregisterNetworkCallback(it)
        }
        networkCallback = null
        wifiLock?.takeIf { it.isHeld }?.release()
        wakeLock?.takeIf { it.isHeld }?.release()
        wifiLock = null
        wakeLock = null
        super.onDestroy()
    }

    companion object {
        const val NOTIFICATION_ID = 1
        const val ACTION_STOP = "pl.sp8mb.owrx.STOP"

        fun start(context: Context) {
            context.startForegroundService(Intent(context, OwrxForegroundService::class.java))
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, OwrxForegroundService::class.java).setAction(ACTION_STOP)
            )
        }
    }
}
