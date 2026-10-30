package com.mehmetdogru.calltracker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat

object NotificationHelper {

    private const val TAG = "NotificationHelper"
    const val CHANNEL_ID = "unreturned_callbacks"
    private const val PREFS_NAME = "calltracker_notifications"
    private const val KEY_ACTIVE_ALERTS = "active_unreturned_phone_numbers"
    private const val BASE_NOTIFICATION_ID = 50000

    data class UnreturnedAlert(
        val phoneNumber: String,
        val callerName: String?,
        val elapsedText: String,
        val message: String
    )

    fun createNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "Cevapsız Arama Hatırlatıcıları"
            val descriptionText = "1 saat boyunca geri dönüş yapılmayan cevapsız çağrılar için hatırlatma uyarıları"
            val importance = NotificationManager.IMPORTANCE_HIGH
            val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
                description = descriptionText
                enableLights(true)
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 500, 200, 500)
                setShowBadge(true)
                lockscreenVisibility = NotificationCompat.VISIBILITY_PUBLIC
            }
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun getNotificationIdForPhone(phoneNumber: String): Int {
        val clean = phoneNumber.filter { it.isDigit() }
        val hash = if (clean.isNotEmpty()) {
            clean.takeLast(6).toIntOrNull() ?: Math.abs(phoneNumber.hashCode() % 10000)
        } else {
            Math.abs(phoneNumber.hashCode() % 10000)
        }
        return BASE_NOTIFICATION_ID + (hash % 40000)
    }

    fun showUnreturnedCallNotification(
        context: Context,
        phoneNumber: String,
        callerName: String?,
        elapsedText: String,
        customMessage: String? = null
    ) {
        if (phoneNumber.isBlank()) return
        createNotificationChannel(context)

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notifId = getNotificationIdForPhone(phoneNumber)

        val cleanPhone = phoneNumber.filter { it.isDigit() || it == '+' }
        val nameDisplay = if (!callerName.isNullOrBlank() && callerName != phoneNumber) " ($callerName)" else ""
        val textBody = customMessage?.takeIf { it.isNotBlank() }
            ?: "Bu numaraya $elapsedText dönüş yapılmadı: $phoneNumber$nameDisplay"

        // Bildirime dokunulduğunda hemen telefon arama ekranını aç (ACTION_DIAL)
        val dialIntent = Intent(Intent.ACTION_DIAL).apply {
            data = Uri.parse("tel:$cleanPhone")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        val dialPendingIntent = PendingIntent.getActivity(
            context,
            notifId,
            dialIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // WhatsApp ile mesaj/arama butonu
        val digits = phoneNumber.filter { it.isDigit() }
        val intlPhone = if (digits.startsWith("0")) "9$digits" else if (digits.startsWith("90")) digits else "90$digits"
        val waIntent = Intent(Intent.ACTION_VIEW).apply {
            data = Uri.parse("https://wa.me/$intlPhone")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        val waPendingIntent = PendingIntent.getActivity(
            context,
            notifId + 1,
            waIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val soundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_missed_call)
            .setContentTitle("⚠️ Geri Dönüş Yapılmadı!")
            .setContentText(textBody)
            .setStyle(NotificationCompat.BigTextStyle().bigText(textBody))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setContentIntent(dialPendingIntent)
            .setAutoCancel(true)
            .setSound(soundUri)
            .setVibrate(longArrayOf(0, 500, 200, 500))
            .setColor(0xFFE11D48.toInt()) // Kırmızı / Amber uyarı rengi
            .addAction(android.R.drawable.ic_menu_call, "📞 Hemen Ara", dialPendingIntent)
            .addAction(android.R.drawable.ic_dialog_info, "💬 WhatsApp", waPendingIntent)

        nm.notify(notifId, builder.build())
        Log.d(TAG, "🔔 Unreturned call notification shown for $phoneNumber (ID: $notifId)")

        // Aktif bildirimler listesine ekle
        recordActiveNotification(context, phoneNumber)
    }

    fun cancelUnreturnedCallNotification(context: Context, phoneNumber: String) {
        if (phoneNumber.isBlank()) return
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notifId = getNotificationIdForPhone(phoneNumber)
        nm.cancel(notifId)
        removeActiveNotification(context, phoneNumber)
        Log.d(TAG, "🔕 Notification cancelled for $phoneNumber (ID: $notifId)")
    }

    /**
     * CRM sunucusundan dönen aktif uyarılarla telefon bildirimlerini senkronize eder.
     * Geri dönüş yapılmış numaraların bildirimleri otomatik olarak kapatılır!
     */
    fun syncUnreturnedAlerts(context: Context, serverAlerts: List<UnreturnedAlert>) {
        val serverPhones = serverAlerts.map { it.phoneNumber }.toSet()
        val currentlyActive = getActiveNotifications(context)

        // Artık beklemeyen (arama yapılmış) bildirimleri iptal et
        for (phone in currentlyActive) {
            if (!serverPhones.contains(phone)) {
                cancelUnreturnedCallNotification(context, phone)
            }
        }

        // Sunucudan gelen uyarıları göster/güncelle
        for (alert in serverAlerts) {
            showUnreturnedCallNotification(
                context = context,
                phoneNumber = alert.phoneNumber,
                callerName = alert.callerName,
                elapsedText = alert.elapsedText,
                customMessage = alert.message
            )
        }
    }

    private fun getPrefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun getActiveNotifications(context: Context): Set<String> {
        return getPrefs(context).getStringSet(KEY_ACTIVE_ALERTS, emptySet())?.toSet() ?: emptySet()
    }

    private fun recordActiveNotification(context: Context, phoneNumber: String) {
        val current = getActiveNotifications(context).toMutableSet()
        current.add(phoneNumber)
        getPrefs(context).edit().putStringSet(KEY_ACTIVE_ALERTS, current).apply()
    }

    private fun removeActiveNotification(context: Context, phoneNumber: String) {
        val current = getActiveNotifications(context).toMutableSet()
        current.remove(phoneNumber)
        getPrefs(context).edit().putStringSet(KEY_ACTIVE_ALERTS, current).apply()
    }
}
