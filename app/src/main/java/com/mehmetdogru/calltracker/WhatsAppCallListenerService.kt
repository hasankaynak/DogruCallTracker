package com.mehmetdogru.calltracker

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

class WhatsAppCallListenerService : NotificationListenerService() {

    companion object {
        private const val TAG = "WhatsAppCallListener"
        // Çağrı tekrarını önlemek için son arayan bilgisini tutalım
        // CT-010: NotificationListenerService callback'leri farkli thread'lerden
        // gelebilir; senkronizasyon olmadan tekrar korumasi guvenilir calismaz.
        @Volatile private var lastCallerTitle: String? = null
        @Volatile private var lastCallTime: Long = 0
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        super.onNotificationPosted(sbn)
        
        val packageName = sbn.packageName
        if (packageName == "com.whatsapp" || packageName == "com.whatsapp.w4b") {
            val notification = sbn.notification
            val extras = notification.extras
            
            val title = extras.getString(Notification.EXTRA_TITLE) ?: ""
            val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
            
            val lowerText = text.lowercase()
            
            // "Gelen sesli arama", "Gelen görüntülü arama", "Incoming call"
            if (lowerText.contains("arama") || lowerText.contains("call") || lowerText.contains("gelen") || lowerText.contains("incoming")) {
                
                val currentTime = System.currentTimeMillis()
                // Aynı kişiden gelen bildirimi 5 saniye içinde tekrar yollama (spam engelleme)
                if (title == lastCallerTitle && (currentTime - lastCallTime) < 5000) {
                    return
                }

                Log.d(TAG, "🟢 DETECTED WHATSAPP INCOMING CALL FROM: $title")
                lastCallerTitle = title
                lastCallTime = currentTime

                // Numarayı WhatsApp sağlamadığı için isim (title) gönderiyoruz
                ApiService.sendIncomingCall(applicationContext, "", "WhatsApp", callerName = title)
            }
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        super.onNotificationRemoved(sbn)
        
        val packageName = sbn.packageName
        if (packageName == "com.whatsapp" || packageName == "com.whatsapp.w4b") {
            val notification = sbn.notification
            val extras = notification.extras
            
            val title = extras.getString(Notification.EXTRA_TITLE) ?: ""
            
            if (title == lastCallerTitle) {
                // Bildirim kapandıysa çağrı bitmiş veya cevapsız kalmıştır. 
                // Şu an için sadece "end" atıyoruz (CallCenter mantığı masaüstü kapatır)
                Log.d(TAG, "🔴 WhatsApp call notification removed for: $title")
                ApiService.sendCallEnded(applicationContext, "", 0, "WhatsApp", callerName = title)
                lastCallerTitle = null
            }
        }
    }
}
