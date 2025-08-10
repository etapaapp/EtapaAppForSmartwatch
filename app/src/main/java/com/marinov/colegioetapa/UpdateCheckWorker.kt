package com.marinov.colegioetapa

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.annotation.RequiresPermission
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

class UpdateCheckWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    companion object {
        private const val CHANNEL_ID = "update_channel"
        private const val NOTIFICATION_ID = 1001
    }

    @RequiresApi(Build.VERSION_CODES.N)
    override suspend fun doWork(): Result {
        UpdateChecker.checkForUpdate(applicationContext, object : UpdateChecker.UpdateListener {
            @RequiresPermission(Manifest.permission.POST_NOTIFICATIONS)
            override fun onUpdateAvailable(url: String) {
                showNotification()
            }

            override fun onUpToDate() {
                // Não é necessário ação
            }

            override fun onError(message: String) {
                // Tratar erro conforme necessário
            }
        })
        return Result.success()
    }

    @RequiresPermission(Manifest.permission.POST_NOTIFICATIONS)
    private fun showNotification() {
        createNotificationChannel()

        val intent = Intent(applicationContext, SettingsFragment::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                putExtra("open_update_directly", true)
            }
        }

        val pendingIntentFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }

        val pendingIntent = PendingIntent.getActivity(
            applicationContext,
            0,
            intent,
            pendingIntentFlags
        )

        val builder = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_update)
            .setContentTitle("Atualização do software disponível!")
            .setContentText("Clique aqui para atualizar")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)

        NotificationManagerCompat.from(applicationContext).notify(NOTIFICATION_ID, builder.build())
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Atualizações do software",
                NotificationManager.IMPORTANCE_HIGH
            )
            val manager = applicationContext.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }
}