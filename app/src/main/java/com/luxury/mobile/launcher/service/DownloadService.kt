package com.luxury.mobile.launcher.service

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
import android.util.Log
import androidx.core.app.NotificationCompat
import com.luxury.mobile.launcher.R
import com.luxury.mobile.launcher.core.DownloadController
import com.luxury.mobile.launcher.ui.InstallActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Long-lived foreground service that owns the download lifecycle.
 *
 * Why a service and not just a coroutine on the activity?
 *   * The original launcher started download work inside `InstallActivity`, so
 *     leaving the activity (back press, home button) often left the download
 *     process to be reaped by Android. This service is started with
 *     `startForegroundService` and immediately enters the foreground with a
 *     persistent notification, telling the OS to keep the process alive for
 *     the duration of the transfer.
 *   * The service observes [DownloadController.state] and rebuilds the
 *     notification on every progress beat. The activity also observes the
 *     same flow when it is alive, so the UI and the notification stay in
 *     perfect sync — but the activity is no longer required for progress to
 *     advance.
 *   * `START_STICKY` plus the persisted `.part` / `.meta` files means that
 *     even if the OS reclaims memory and later restarts the service, the
 *     controller will pick up exactly where it left off.
 */
class DownloadService : Service() {

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var observerJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
        // Enter foreground immediately so the system grants the service a
        // background CPU/network budget. The actual notification content is
        // refined as soon as the controller emits its first state.
        startForegroundCompat(buildNotification(initialText()))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val controller = DownloadController.get(applicationContext)
        controller.start()

        if (observerJob == null) {
            observerJob = scope.launch {
                controller.state.collectLatest { state ->
                    val notification = buildNotification(textFor(state))
                    notificationManager().notify(NOTIFICATION_ID, notification)
                    when (state) {
                        is DownloadController.State.Done -> stopSelfClean()
                        is DownloadController.State.Error -> if (!state.recoverable) stopSelfClean()
                        else -> Unit
                    }
                }
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        Log.i(TAG, "Service onDestroy")
        observerJob?.cancel()
        observerJob = null
        // Note: we do NOT cancel the controller here. Stopping the service is
        // expected at the end of a successful run; cancelling the controller
        // mid-run would lose progress. Cancellation only happens via explicit
        // [DownloadController.cancel] calls from the UI.
        super.onDestroy()
    }

    private fun stopSelfClean() {
        stopForegroundCompat()
        stopSelf()
    }

    private fun initialText(): String = getString(R.string.notification_text_downloading, 0)

    private fun textFor(state: DownloadController.State): String = when (state) {
        is DownloadController.State.Idle,
        is DownloadController.State.FetchingManifest,
        is DownloadController.State.CheckingFiles -> getString(R.string.status_checking)
        is DownloadController.State.Downloading -> {
            val pct = if (state.totalBytes > 0) {
                ((state.downloadedBytes * 100L) / state.totalBytes).toInt().coerceIn(0, 100)
            } else 0
            getString(R.string.notification_text_downloading, pct)
        }
        is DownloadController.State.Extracting -> getString(R.string.notification_text_extracting)
        is DownloadController.State.Done -> getString(R.string.notification_text_finished)
        is DownloadController.State.Error -> if (state.recoverable) {
            getString(R.string.notification_text_paused)
        } else {
            getString(R.string.status_error, state.message)
        }
    }

    private fun buildNotification(text: String): Notification {
        val tapIntent = Intent(this, InstallActivity::class.java).addFlags(
            Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        )
        val tapFlags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)
        val tapPi = PendingIntent.getActivity(this, 0, tapIntent, tapFlags)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(tapPi)
            .build()
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    @Suppress("DEPRECATION")
    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= 24) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            stopForeground(true)
        }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = notificationManager()
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            setShowBadge(false)
            enableLights(false)
            enableVibration(false)
        }
        nm.createNotificationChannel(channel)
    }

    private fun notificationManager(): NotificationManager =
        getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    companion object {
        private const val TAG = "DownloadService"
        private const val CHANNEL_ID = "havana_download_v2"
        private const val NOTIFICATION_ID = 0x4845

        fun start(context: Context) {
            val intent = Intent(context, DownloadService::class.java)
            if (Build.VERSION.SDK_INT >= 26) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}
