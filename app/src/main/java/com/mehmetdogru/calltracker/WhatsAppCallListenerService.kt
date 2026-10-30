package com.mehmetdogru.calltracker

import android.app.Notification
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

class WhatsAppCallListenerService : NotificationListenerService() {

    companion object {
        private const val TAG = "WhatsAppCallListener"
        // Çağrı tekrarını önlemek için son arayan bilgisini tutalım
        // CT-010: NotificationListenerService callback'leri farklı thread'lerden
        // gelebilir; senkronizasyon olmadan tekrar koruması güvenilir çalışmaz.
        @Volatile private var lastCallerTitle: String? = null
        @Volatile private var lastCallTime: Long = 0
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        super.onNotificationPosted(sbn)
        
        val packageName = sbn.packageName
        if (packageName == "com.whatsapp" || packageName == "com.whatsapp.w4b") {
            val notification = sbn.notification

            // 1) Eğer bildirim kategori olarak MESSAGE, EMAIL veya PROMO ise kesinlikle ÇAĞRI DEĞİLDİR (Sohbet Mesajı)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val cat = notification.category
                if (cat == Notification.CATEGORY_MESSAGE ||
                    cat == Notification.CATEGORY_EMAIL ||
                    cat == Notification.CATEGORY_PROMO ||
                    cat == Notification.CATEGORY_SOCIAL ||
                    cat == Notification.CATEGORY_PROGRESS) {
                    return
                }
            }

            // 2) Eğer bildirimde "Yanıtla / Reply" (RemoteInput) butonu varsa bu bir sohbet mesajıdır!
            val hasRemoteInput = notification.actions?.any { action ->
                action.remoteInputs?.isNotEmpty() == true
            } ?: false
            if (hasRemoteInput) {
                return
            }

            val extras = notification.extras
            val title = extras.getString(Notification.EXTRA_TITLE) ?: ""
            val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
            val subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString() ?: ""
            val template = extras.getString(Notification.EXTRA_TEMPLATE) ?: ""

            // 3) MessagingStyle mesajlaşma şablonu içeriyorsa çağrı değildir
            if (template.contains("MessagingStyle") || extras.containsKey(Notification.EXTRA_MESSAGING_PERSON)) {
                return
            }

            val lowerText = text.lowercase()
            val lowerSub = subText.lowercase()
            val combined = "$lowerText $lowerSub"

            // Mesaj belirten kelimeler varsa çağrı değildir
            if (combined.contains("yeni mesaj") || combined.contains("new message") || 
                combined.contains("mesaj") || combined.contains("message")) {
                return
            }

            // 4) Gerçek WhatsApp Sesli / Görüntülü Arama Tespiti:
            // Android çağrı kategorisi veya açıkça arama olduğunu belirten ifadeler
            val isCallCategory = (notification.category == Notification.CATEGORY_CALL)
            val isExplicitCallText = combined.contains("sesli arama") || 
                                     combined.contains("görüntülü arama") ||
                                     combined.contains("voice call") ||
                                     combined.contains("video call") ||
                                     combined.contains("arama yapılıyor") ||
                                     combined.contains("calling...")

            if (isCallCategory || isExplicitCallText) {
                val currentTime = System.currentTimeMillis()
                // Aynı kişiden gelen bildirimi 5 saniye içinde tekrar yollama (spam engelleme)
                if (title == lastCallerTitle && (currentTime - lastCallTime) < 5000) {
                    return
                }

                Log.d(TAG, "🟢 DETECTED REAL WHATSAPP INCOMING CALL FROM: $title")
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
            
            if (title.isNotBlank() && title == lastCallerTitle) {
                // Bildirim kapandıysa çağrı bitmiş veya cevapsız kalmıştır. 
                Log.d(TAG, "🔴 WhatsApp call notification removed for: $title")
                ApiService.sendCallEnded(applicationContext, "", 0, "WhatsApp", callerName = title)
                lastCallerTitle = null
            }
        }
    }
}
