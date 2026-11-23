package com.ck.restro

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
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.ck.restro.engine.RestoreJob

/**
 * Keeps Restro alive while a photo is being restored (it can take minutes with Ultra detail),
 * with a progress notification. Stops itself as soon as the job ends.
 */
class RestoreService : Service() {

    private val listener: (RestoreJob.State) -> Unit = { s -> onState(s) }
    private var lastPct = -1

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        channels(this)
        val n = build("Starting…", 0, true)
        if (Build.VERSION.SDK_INT >= 34) startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(ID, n)
        RestoreJob.addListener(listener)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) RestoreJob.cancel()
        return START_NOT_STICKY
    }

    private fun onState(s: RestoreJob.State) {
        val nm = getSystemService(NotificationManager::class.java)
        when (s) {
            is RestoreJob.State.Running -> {
                val pct = (s.progress * 100).toInt()
                if (pct == lastPct) return
                lastPct = pct
                val eta = if (s.etaSec >= 0) " · ${fmtEta(s.etaSec)} left" else ""
                nm.notify(ID, build("${s.stage}$eta", pct, false))
            }
            is RestoreJob.State.Done -> { finished(nm, "Photo restored", s.info); stopSelf() }
            is RestoreJob.State.Failed -> { finished(nm, "Restore failed", s.message); stopSelf() }
            RestoreJob.State.Idle -> stopSelf()
        }
    }

    private fun finished(nm: NotificationManager, title: String, text: String) {
        val n = NotificationCompat.Builder(this, CH_DONE)
            .setSmallIcon(R.drawable.ic_wand)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(openApp())
            .setAutoCancel(true)
            .build()
        nm.notify(ID_DONE, n)
    }

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun build(text: String, pct: Int, indeterminate: Boolean): Notification {
        val cancel = PendingIntent.getService(
            this, 1, Intent(this, RestoreService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CH_WORK)
            .setSmallIcon(R.drawable.ic_wand)
            .setContentTitle("Restoring photo")
            .setContentText(text)
            .setProgress(100, pct, indeterminate)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp())
            .addAction(R.drawable.ic_close, "Cancel", cancel)
            .build()
    }

    override fun onDestroy() {
        RestoreJob.removeListener(listener)
        super.onDestroy()
    }

    companion object {
        private const val ID = 7
        private const val ID_DONE = 8
        private const val CH_WORK = "restore"
        private const val CH_DONE = "restore_done"
        private const val ACTION_CANCEL = "com.ck.restro.CANCEL"

        fun start(ctx: Context) {
            runCatching { ContextCompat.startForegroundService(ctx, Intent(ctx, RestoreService::class.java)) }
        }

        fun channels(ctx: Context) {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CH_WORK, "Restoring", NotificationManager.IMPORTANCE_LOW))
            nm.createNotificationChannel(NotificationChannel(CH_DONE, "Finished photos", NotificationManager.IMPORTANCE_DEFAULT))
        }

        fun fmtEta(s: Int) = if (s >= 60) "${s / 60} min ${s % 60} s" else "$s s"
    }
}
