package ru.resalemonitor

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

object Notifier {
    const val CH_SERVICE = "scanner"
    const val CH_ALERT = "alerts"
    const val ID_SERVICE = 1
    const val ID_CAPTCHA = 2

    fun createChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_SERVICE, "Фоновый сканер", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Постоянное уведомление работающего сканера"
                setShowBadge(false)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_ALERT, "Капча и ошибки", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Требуется действие пользователя"
                enableVibration(true)
            }
        )
    }

    private fun openApp(ctx: Context): PendingIntent = PendingIntent.getActivity(
        ctx, 0,
        Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    fun service(ctx: Context, text: String): Notification {
        val stop = PendingIntent.getService(
            ctx, 1,
            Intent(ctx, ResaleParserService::class.java).setAction(ResaleParserService.ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(ctx, CH_SERVICE)
            .setSmallIcon(R.drawable.ic_stat_scan)
            .setContentTitle("Resale Monitor работает")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp(ctx))
            .addAction(0, "Стоп", stop)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    fun update(ctx: Context, text: String) = post(ctx, ID_SERVICE, service(ctx, text))

    fun captcha(ctx: Context) = post(
        ctx, ID_CAPTCHA,
        NotificationCompat.Builder(ctx, CH_ALERT)
            .setSmallIcon(R.drawable.ic_stat_scan)
            .setContentTitle("⚠️ Требуется ввод капчи")
            .setContentText("Откройте приложение и пройдите проверку в WebView")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setContentIntent(openApp(ctx))
            .setAutoCancel(true)
            .build()
    )

    fun cancelCaptcha(ctx: Context) = NotificationManagerCompat.from(ctx).cancel(ID_CAPTCHA)

    private fun post(ctx: Context, id: Int, n: Notification) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        try {
            NotificationManagerCompat.from(ctx).notify(id, n)
        } catch (_: SecurityException) {
        }
    }

    /** Громкий сигнал (будильник) + вибрация, чтобы заметить капчу. */
    fun beep(ctx: Context) {
        try {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            val ringtone = RingtoneManager.getRingtone(ctx, uri)
            if (ringtone != null) {
                ringtone.audioAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
                ringtone.play()
                Handler(Looper.getMainLooper()).postDelayed({ ringtone.stop() }, 4_000)
            }
        } catch (e: Exception) {
            AppLog.w("не удалось проиграть звук: ${e.message}")
        }
        try {
            val vibrator = if (Build.VERSION.SDK_INT >= 31) {
                ctx.getSystemService(VibratorManager::class.java).defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                ctx.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }
            vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 600, 300, 600, 300, 600), -1))
        } catch (_: Exception) {
        }
    }
}
