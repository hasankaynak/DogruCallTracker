package com.mehmetdogru.calltracker

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.IBinder
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.app.NotificationCompat

class CallDetectorService : Service() {

    private var callReceiver: CallReceiver? = null
    // CT-002: Ag geri geldiginde kuyrugu bosaltmak icin
    private var networkCallback: android.net.ConnectivityManager.NetworkCallback? = null
    private var telephonyManager: TelephonyManager? = null
    @Suppress("DEPRECATION")
    private var phoneStateListener: PhoneStateListener? = null
    private var telephonyCallback: Any? = null  // TelephonyCallback (API 31+)

    // Çağrı durum takibi
    // CT-010: Bu alanlar iki ayri thread'den yaziliyor —
    //   (a) TelephonyCallback  -> mainExecutor
    //   (b) CallReceiver / outgoingCallReceiver -> broadcast thread'i
    // @Volatile olmadan bir thread'in yazdigini digeri gormeyebilir; sonuc
    // cift gonderim veya numaranin kaybolmasidir. Referans/ilkel atamalar
    // atomik oldugu icin @Volatile bu kullanim icin yeterlidir.
    @Volatile private var lastCallState = TelephonyManager.CALL_STATE_IDLE
    @Volatile private var lastNumber: String? = null
    @Volatile private var callStartTime: Long = 0
    @Volatile private var isIncoming = false
    @Volatile private var alreadyNotified = false

    private val heartbeatHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val heartbeatRunnable = object : Runnable {
        override fun run() {
            checkDeviceActiveStatus()
            checkLocalOfflineMissedCalls()
            heartbeatHandler.postDelayed(this, 120_000L) // 2 dakikada bir kontrol
        }
    }

    companion object {
        const val CHANNEL_ID = "call_tracker_channel"
        const val NOTIFICATION_ID = 1001
        const val TAG = "CallDetectorService"
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "═══ SERVİS OLUŞTURULDU ═══")
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "═══ SERVİS BAŞLATILDI ═══")

        val notificationIntent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Doğru CRM Çağrı Takip")
            .setContentText("🟢 Gelen ve giden aramalar dinleniyor...")
            .setSmallIcon(android.R.drawable.ic_menu_call)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()

        notification.flags = notification.flags or
            Notification.FLAG_NO_CLEAR or
            Notification.FLAG_ONGOING_EVENT

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        // ═══ TelephonyManager listener — numara HEMEN gelir ═══
        registerTelephonyListener()

        // Broadcast receiver da kaydet (yedek)
        registerCallReceiver()

        // ═══ CT-002: Offline kuyruk ═══
        // Servis her basladiginda bekleyen kayitlari gondermeyi dene
        // (telefon yeniden basladiktan sonraki ilk firsat burasidir).
        ApiService.drainQueue(this)
        registerNetworkCallback()
        heartbeatHandler.removeCallbacks(heartbeatRunnable)
        heartbeatHandler.post(heartbeatRunnable)

        getSharedPreferences("calltracker", MODE_PRIVATE)
            .edit()
            .putBoolean("service_running", true)
            .putLong("service_start_time", System.currentTimeMillis())
            .apply()

        return START_STICKY
    }

    /**
     * TelephonyManager ile çağrı durumu dinle
     * Bu yöntem RINGING durumunda numarayı doğrudan verir
     */
    @Suppress("DEPRECATION")
    private fun registerTelephonyListener() {
        telephonyManager = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Android 12+ → TelephonyCallback
            try {
                val callback = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
                    override fun onCallStateChanged(state: Int) {
                        handleCallState(state, null)
                    }
                }
                telephonyManager?.registerTelephonyCallback(mainExecutor, callback)
                telephonyCallback = callback
                Log.d(TAG, "TelephonyCallback (API 31+) kaydedildi")
            } catch (e: Exception) {
                Log.e(TAG, "TelephonyCallback hatası: ${e.message}")
                registerLegacyListener()
            }
        } else {
            registerLegacyListener()
        }
    }

    @Suppress("DEPRECATION")
    private fun registerLegacyListener() {
        phoneStateListener = object : PhoneStateListener() {
            override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                Log.d(TAG, "═══ PhoneStateListener ═══ state=$state number=${mask(phoneNumber)}")
                handleCallState(state, phoneNumber)
            }
        }
        telephonyManager?.listen(phoneStateListener, PhoneStateListener.LISTEN_CALL_STATE)
        Log.d(TAG, "PhoneStateListener kaydedildi")
    }


    /**
     * CT-018: Telefon numarasini loglarda maskele.
     * logcat'e erisebilen her uygulama musteri numaralarini gorebiliyordu (KVKK).
     * Hata ayiklama derlemesinde tam numara, uretimde yalnizca son 2 hane gosterilir.
     */
    private fun mask(number: String?): String {
        if (number.isNullOrEmpty()) return "YOK"
        if (BuildConfig.DEBUG) return number
        return if (number.length <= 2) "**" else "*".repeat(number.length - 2) + number.takeLast(2)
    }

    private fun handleCallState(state: Int, phoneNumber: String?) {
        if (state == lastCallState && phoneNumber.isNullOrEmpty()) return

        Log.d(TAG, "══ ÇAĞRI DURUMU ══ $lastCallState → $state | numara=${mask(phoneNumber)}")

        when (state) {
            TelephonyManager.CALL_STATE_RINGING -> {
                if (lastCallState != TelephonyManager.CALL_STATE_RINGING) {
                    // YENİ ÇAĞRI BAŞLADI
                    isIncoming = true
                    alreadyNotified = false
                    lastNumber = null // Eski numarayı kesinlikle temizle
                    
                    // Numarayı al (Callback'ten veya BroadcastReceiver'dan)
                    if (!phoneNumber.isNullOrEmpty()) {
                        lastNumber = phoneNumber
                    } else if (!CallReceiver.lastCapturedIncomingNumber.isNullOrEmpty()) {
                        lastNumber = CallReceiver.lastCapturedIncomingNumber
                    }
                } else {
                    // HALA ÇALIYOR, numara yeni düştüyse al
                    if (lastNumber.isNullOrEmpty()) {
                        if (!phoneNumber.isNullOrEmpty()) lastNumber = phoneNumber
                        else if (!CallReceiver.lastCapturedIncomingNumber.isNullOrEmpty()) {
                            lastNumber = CallReceiver.lastCapturedIncomingNumber
                        }
                    }
                }

                Log.d(TAG, "🔍 Tespit edilen numara: ${mask(lastNumber)}")
                
                // Numara varsa ve henüz bildirim atılmadıysa API'ye gönder
                if (!lastNumber.isNullOrEmpty() && !alreadyNotified) {
                    alreadyNotified = true
                    val targetNum = lastNumber!!
                    Log.d(TAG, "🚀 API'ye gönderiliyor: ${mask(targetNum)}")
                    ApiService.sendIncomingCall(this, targetNum, "GSM")
                    
                    val time = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
                    getSharedPreferences("calltracker", MODE_PRIVATE).edit()
                        .putInt("call_count", getSharedPreferences("calltracker", MODE_PRIVATE).getInt("call_count", 0) + 1)
                        .putString("last_call", "📞 $targetNum — $time")
                        .apply()
                }
            }

            TelephonyManager.CALL_STATE_OFFHOOK -> {
                callStartTime = System.currentTimeMillis()
                if (lastCallState != TelephonyManager.CALL_STATE_RINGING) {
                    isIncoming = false
                    // Giden arama basladiysa bu numaraya ait cevapsiz bildirimini hemen kapat
                    lastNumber?.let {
                        NotificationHelper.cancelUnreturnedCallNotification(this, it)
                        removeLocalMissedCall(it)
                    }
                }
                // Çağrı cevalanıyor → API'ye bildir → masaüstünde süre sayacı başlar
                if (isIncoming && !lastNumber.isNullOrEmpty()) {
                    Log.d(TAG, "📱 Çağrı cevaplanıyor → API bildirim: ${mask(lastNumber)}")
                    ApiService.sendCallAnswered(this, lastNumber!!, "GSM")
                    NotificationHelper.cancelUnreturnedCallNotification(this, lastNumber!!)
                    removeLocalMissedCall(lastNumber!!)
                }
                Log.d(TAG, "📱 Çağrı cevaplanıdı")
            }

            TelephonyManager.CALL_STATE_IDLE -> {
                if (lastCallState == TelephonyManager.CALL_STATE_OFFHOOK) {
                    val duration = ((System.currentTimeMillis() - callStartTime) / 1000).toInt()

                    if (isIncoming) {
                        // ═══ GELEN ÇAĞRI BİTTİ ═══
                        // CT-006: Android 12+ TelephonyCallback numara vermez ve
                        // EXTRA_INCOMING_NUMBER cogu zaman null doner. Numara yakalanamadiysa
                        // cagri TAMAMEN kayboluyordu. Simdi CallLog'dan tamamlaniyor.
                        //
                        // Neden burada (IDLE) ve RINGING'de degil: CallLog kaydi cagri
                        // BITTIGINDE yazilir. RINGING sirasinda sorgulanirsa BIR ONCEKI
                        // cagrinin numarasi doner ve cagri YANLIS musteriye baglanir.
                        if (lastNumber.isNullOrEmpty()) {
                            lastNumber = getLastIncomingNumber()
                            if (!lastNumber.isNullOrEmpty()) {
                                Log.d(TAG, "🔎 Numara CallLog'dan tamamlandı (gelen)")
                                // Gercek zamanli popup kacirildi ama kayit kaybolmuyor.
                                ApiService.sendIncomingCall(this, lastNumber!!, "GSM")
                            }
                        }
                        Log.d(TAG, "📵 GELEN çağrı bitti — ${duration}sn")
                        if (!lastNumber.isNullOrEmpty()) {
                            ApiService.sendCallEnded(this, lastNumber!!, duration, "GSM")
                        }
                    } else {
                        // ═══ GİDEN ÇAĞRI BİTTİ ═══
                        // Numara yoksa CallLog'dan almayı dene
                        if (lastNumber.isNullOrEmpty()) {
                            lastNumber = getLastOutgoingNumber()
                        }
                        Log.d(TAG, "📤 GİDEN çağrı bitti — ${duration}sn — ${mask(lastNumber)}")
                        if (!lastNumber.isNullOrEmpty()) {
                            NotificationHelper.cancelUnreturnedCallNotification(this, lastNumber!!)
                            removeLocalMissedCall(lastNumber!!)
                            ApiService.sendOutgoingCall(this, lastNumber!!, duration, "GSM")
                        }
                    }

                    // Prefs güncelle
                    val direction = if (isIncoming) "Gelen" else "Giden"
                    val displayNumber = lastNumber ?: "Bilinmeyen"
                    val durationText = "${duration / 60}dk ${duration % 60}sn"
                    getSharedPreferences("calltracker", MODE_PRIVATE).edit()
                        .putString("last_call", "${if (isIncoming) "📥" else "📤"} $direction: $displayNumber — $durationText")
                        .apply()

                } else if (lastCallState == TelephonyManager.CALL_STATE_RINGING) {
                    // ═══ CEVAPSIZ ÇAĞRI ═══
                    // CT-006: Numara yakalanamadiysa CallLog'dan tamamla
                    if (lastNumber.isNullOrEmpty()) {
                        // Android CallLog'a yazım işlemi asenkron olduğundan 300ms bekleyip sorgula
                        try { Thread.sleep(300) } catch (_: Exception) {}
                        lastNumber = getLastMissedOrIncomingNumber()
                        if (lastNumber.isNullOrEmpty() && !CallReceiver.lastCapturedIncomingNumber.isNullOrEmpty()) {
                            lastNumber = CallReceiver.lastCapturedIncomingNumber
                        }
                        if (!lastNumber.isNullOrEmpty()) Log.d(TAG, "🔎 Numara CallLog/Broadcast'ten tamamlandı (cevapsız): ${mask(lastNumber)}")
                    }
                    Log.d(TAG, "📵 Cevapsız çağrı: ${mask(lastNumber)}")
                    if (!lastNumber.isNullOrEmpty()) {
                        val targetNum = lastNumber!!
                        saveLocalMissedCall(targetNum, System.currentTimeMillis())
                        ApiService.sendMissedCall(this, targetNum, "GSM")

                        val time = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
                        getSharedPreferences("calltracker", MODE_PRIVATE).edit()
                            .putInt("call_count", getSharedPreferences("calltracker", MODE_PRIVATE).getInt("call_count", 0) + 1)
                            .putString("last_call", "📵 Cevapsız: $targetNum — $time")
                            .apply()
                    }
                }

                // Sıfırla
                lastNumber = null
                CallReceiver.lastCapturedIncomingNumber = null
                isIncoming = false
                callStartTime = 0
                alreadyNotified = false
            }
        }

        lastCallState = state
    }

    private var outgoingCallReceiver: BroadcastReceiver? = null

    private fun registerCallReceiver() {
        if (callReceiver == null) {
            callReceiver = CallReceiver()
            val filter = IntentFilter().apply {
                addAction(TelephonyManager.ACTION_PHONE_STATE_CHANGED)
                priority = IntentFilter.SYSTEM_HIGH_PRIORITY
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(callReceiver, filter, RECEIVER_EXPORTED)
            } else {
                registerReceiver(callReceiver, filter)
            }
        }

        // Giden çağrı numarasını yakala — servis içinden
        if (outgoingCallReceiver == null) {
            outgoingCallReceiver = object : BroadcastReceiver() {
                @Suppress("DEPRECATION")
                override fun onReceive(context: Context?, intent: Intent?) {
                    if (intent?.action == Intent.ACTION_NEW_OUTGOING_CALL) {
                        val number = intent.getStringExtra(Intent.EXTRA_PHONE_NUMBER)
                        Log.d(TAG, "📤 GİDEN ÇAĞRI YAKALANDI (servis): $number")
                        if (!number.isNullOrEmpty()) {
                            lastNumber = number
                            isIncoming = false
                        }
                    }
                }
            }
            val outFilter = IntentFilter().apply {
                @Suppress("DEPRECATION")
                addAction(Intent.ACTION_NEW_OUTGOING_CALL)
                priority = IntentFilter.SYSTEM_HIGH_PRIORITY
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(outgoingCallReceiver, outFilter, RECEIVER_EXPORTED)
            } else {
                registerReceiver(outgoingCallReceiver, outFilter)
            }
        }
    }

    /**
     * Son gelen veya cevapsız çağrı numarasını CallLog'dan al (Android 12+ ve cevapsız aramalar için)
     */
    private fun getLastMissedOrIncomingNumber(): String? {
        try {
            if (checkSelfPermission(android.Manifest.permission.READ_CALL_LOG)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                Log.d(TAG, "READ_CALL_LOG izni yok!")
                return null
            }
            // MISSED_TYPE (3), REJECTED_TYPE (5), INCOMING_TYPE (1)
            val selection = "${android.provider.CallLog.Calls.TYPE} IN (" +
                    "${android.provider.CallLog.Calls.MISSED_TYPE}, " +
                    "${android.provider.CallLog.Calls.REJECTED_TYPE}, " +
                    "${android.provider.CallLog.Calls.INCOMING_TYPE})"
            val cursor = contentResolver.query(
                android.provider.CallLog.Calls.CONTENT_URI,
                arrayOf(android.provider.CallLog.Calls.NUMBER, android.provider.CallLog.Calls.DATE),
                selection,
                null,
                "${android.provider.CallLog.Calls.DATE} DESC"
            )
            cursor?.use {
                if (it.moveToFirst()) {
                    val date = it.getLong(1)
                    if (System.currentTimeMillis() - date <= 60_000L) {
                        val number = it.getString(0)
                        Log.d(TAG, "CallLog son arama: ${mask(number)}")
                        return number
                    } else {
                        Log.d(TAG, "CallLog en son arama çok eski (${System.currentTimeMillis() - date}ms önce), yok sayıldı")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "CallLog arama hatası: ${e.message}")
        }
        return null
    }

    /**
     * Son gelen çağrı numarasını CallLog'dan al (Android 12+ için)
     */
    private fun getLastIncomingNumber(): String? {
        try {
            if (checkSelfPermission(android.Manifest.permission.READ_CALL_LOG)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                Log.d(TAG, "READ_CALL_LOG izni yok!")
                return null
            }
            val cursor = contentResolver.query(
                android.provider.CallLog.Calls.CONTENT_URI,
                arrayOf(android.provider.CallLog.Calls.NUMBER, android.provider.CallLog.Calls.DATE),
                "${android.provider.CallLog.Calls.TYPE} = ${android.provider.CallLog.Calls.INCOMING_TYPE}",
                null,
                "${android.provider.CallLog.Calls.DATE} DESC"
            )
            cursor?.use {
                if (it.moveToFirst()) {
                    val date = it.getLong(1)
                    if (System.currentTimeMillis() - date <= 120_000L) {
                        val number = it.getString(0)
                        Log.d(TAG, "CallLog son gelen: ${mask(number)}")
                        return number
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "CallLog gelen numara hatası: ${e.message}")
        }
        return null
    }

    /**
     * Son giden çağrı numarasını CallLog'dan al
     */
    private fun getLastOutgoingNumber(): String? {
        try {
            if (checkSelfPermission(android.Manifest.permission.READ_CALL_LOG)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) return null
            val cursor = contentResolver.query(
                android.provider.CallLog.Calls.CONTENT_URI,
                arrayOf(android.provider.CallLog.Calls.NUMBER),
                "${android.provider.CallLog.Calls.TYPE} = ${android.provider.CallLog.Calls.OUTGOING_TYPE}",
                null,
                "${android.provider.CallLog.Calls.DATE} DESC"
            )
            cursor?.use {
                if (it.moveToFirst()) return it.getString(0)
            }
        } catch (e: Exception) {
            Log.e(TAG, "CallLog giden numara hatası: ${e.message}")
        }
        return null
    }

    /**
     * CT-002: Ag geri geldiginde kuyrugu otomatik bosalt.
     *
     * Periyodik yoklama (polling) yerine sistem bildirimi kullaniliyor:
     * telefon kapsama disindayken bos yere uyanip pil tuketmez, ag geldigi
     * anda ise beklemeden gonderim baslar.
     */
    private fun registerNetworkCallback() {
        if (networkCallback != null) return
        try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
            val cb = object : android.net.ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: android.net.Network) {
                    Log.d(TAG, "🌐 Ağ geri geldi — kuyruk boşaltılıyor")
                    ApiService.drainQueue(applicationContext)
                }
            }
            val request = android.net.NetworkRequest.Builder()
                .addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            cm.registerNetworkCallback(request, cb)
            networkCallback = cb
            Log.d(TAG, "Ağ dinleyicisi kaydedildi")
        } catch (e: Exception) {
            Log.e(TAG, "Ağ dinleyicisi kaydedilemedi: ${e.message}")
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // CT-004: Kalici PARTIAL_WAKE_LOCK KALDIRILDI.
    //
    // Onceki hali zaman asimsiz acquire() yapip hic birakmiyordu → cihaz asla
    // derin uykuya gecemiyordu ve gun boyu belirgin pil tuketiyordu.
    //
    // Neden guvenli:
    //   - Foreground service sureci zaten canli ve yuksek oncelikli tutar.
    //   - TelephonyCallback / PhoneStateListener callback'lerini SISTEM teslim eder
    //     ve teslim suresince kendi wakelock'unu tutar.
    //   - BroadcastReceiver.onReceive de sistem wakelock'u altinda calisir.
    //   → Cagri algilamak icin uygulamanin kalici wake lock tutmasina gerek YOKTUR.
    //
    // Wake lock'un gercekten gerekli oldugu tek yer ag gonderimidir; orada
    // ApiService kisa sureli ve ZAMAN ASIMLI bir wake lock aliyor (bkz. ApiService.drainQueue).
    // ══════════════════════════════════════════════════════════════════════════

    override fun onDestroy() {
        Log.d(TAG, "═══ SERVİS DURDURULDU ═══")
        callReceiver?.let { try { unregisterReceiver(it) } catch (_: Exception) {} }
        callReceiver = null
        outgoingCallReceiver?.let { try { unregisterReceiver(it) } catch (_: Exception) {} }
        outgoingCallReceiver = null
        
        // Listener'ları kaldır
        @Suppress("DEPRECATION")
        phoneStateListener?.let { telephonyManager?.listen(it, PhoneStateListener.LISTEN_NONE) }
        
        // CT-002: Ag dinleyicisini birak (sizinti onlemi)
        networkCallback?.let {
            try {
                val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
                cm.unregisterNetworkCallback(it)
            } catch (_: Exception) {}
        }
        networkCallback = null


        getSharedPreferences("calltracker", MODE_PRIVATE)
            .edit().putBoolean("service_running", false).apply()

        // CT-005: Kosullu yeniden baslatma.
        restartIfEnabled("onDestroy")
        super.onDestroy()
        heartbeatHandler.removeCallbacks(heartbeatRunnable)
        val isServiceRunning = getSharedPreferences("calltracker", MODE_PRIVATE).getBoolean("service_running", false)
        if (!isServiceRunning) {
            showServiceStoppedNotification()
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // CT-005: Kullanici uygulamayi son gorevlerden kaydirdi — servis devam etmeli,
        // ancak yalnizca kullanici servisi durdurmadiysa.
        restartIfEnabled("onTaskRemoved")
        super.onTaskRemoved(rootIntent)
    }

    /**
     * CT-005: Servisi yalnizca GEREKIYORSA ve GUVENLIYSE yeniden baslatir.
     *
     * Onceki hali kosulsuz startForegroundService() cagiriyordu. Iki ayri problem vardi:
     *
     * 1) Kullanici servisi bilerek durdursa bile geri geliyordu. BootReceiver
     *    'service_enabled' tercihine saygi duyuyordu ama burasi duymuyordu — tutarsizlik.
     *
     * 2) Android 12+ (API 31) arka plandan foreground service baslatmayi kisitlar.
     *    Cagri ForegroundServiceStartNotAllowedException firlatirsa ve yakalanmazsa
     *    onDestroy icinde COKME olur; servis olur ve bir daha kalkmaz.
     *    START_STICKY zaten sistem tarafindan yeniden baslatma saglar — bu yol yedektir.
     */
    private fun restartIfEnabled(kaynak: String) {
        val enabled = getSharedPreferences("calltracker", MODE_PRIVATE)
            .getBoolean("service_enabled", false)

        if (!enabled) {
            Log.d(TAG, "[$kaynak] Servis kullanıcı tarafından durdurulmuş — yeniden başlatılmıyor")
            return
        }

        try {
            val restartIntent = Intent(applicationContext, CallDetectorService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                applicationContext.startForegroundService(restartIntent)
            } else {
                applicationContext.startService(restartIntent)
            }
            Log.d(TAG, "[$kaynak] Servis yeniden başlatıldı")
        } catch (e: Exception) {
            // Android 12+ kisitlamasi veya baska bir hata — COKME. START_STICKY devrede.
            Log.e(TAG, "[$kaynak] Yeniden başlatılamadı (START_STICKY devrede): ${e.message}")
        }
    }


    private fun checkDeviceActiveStatus() {
        ApiService.verifyDeviceToken(this) { ok, _ ->
            val isPassive = getSharedPreferences("calltracker", MODE_PRIVATE).getBoolean("is_device_passive", false)
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (isPassive) {
                // Foreground bildirimini kirmizi pasif uyarisina cevir
                val updatedNotif = androidx.core.app.NotificationCompat.Builder(this, CHANNEL_ID)
                    .setContentTitle("🔴 CİHAZ PASİFE ALINDI!")
                    .setContentText("CRM'den pasife alındı. Aramalar sisteme kaydedilmiyor.")
                    .setSmallIcon(android.R.drawable.stat_notify_error)
                    .setPriority(androidx.core.app.NotificationCompat.PRIORITY_HIGH)
                    .setColor(0xFFEF4444.toInt())
                    .setOngoing(true)
                    .build()
                nm.notify(NOTIFICATION_ID, updatedNotif)
            } else if (ok) {
                // Normal aktif bildirim
                val normalNotif = androidx.core.app.NotificationCompat.Builder(this, CHANNEL_ID)
                    .setContentTitle("Doğru CRM Çağrı Takip")
                    .setContentText("🟢 Gelen ve giden aramalar dinleniyor...")
                    .setSmallIcon(android.R.drawable.ic_menu_call)
                    .setPriority(androidx.core.app.NotificationCompat.PRIORITY_LOW)
                    .setOngoing(true)
                    .build()
                nm.notify(NOTIFICATION_ID, normalNotif)
                nm.cancel(3001)
            }
        }
    }

    private fun showServiceStoppedNotification() {
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val notif = androidx.core.app.NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("⚠️ Çağrı Takip Servisi Durduruldu!")
                .setContentText("Servis kapalı olduğu için aramalar CRM'e aktarılamıyor. Lütfen uygulamayı açıp servisi başlatın.")
                .setSmallIcon(android.R.drawable.stat_sys_warning)
                .setPriority(androidx.core.app.NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .build()
            nm.notify(1002, notif)
        } catch (_: Exception) {}
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Çağrı Takip Servisi",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Gelen aramaları algılar ve Doğru CRM'e bildirir"
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 1 SAATTİR DÖNÜŞ YAPILMAYAN CEVAPSIZ ÇAĞRILARIN ÇEVRİMDIŞI / YEREL TAKİBİ
    // ══════════════════════════════════════════════════════════════════════════

    private fun saveLocalMissedCall(phone: String, timestamp: Long) {
        if (phone.isBlank()) return
        try {
            val prefs = getSharedPreferences("calltracker_offline_missed", MODE_PRIVATE)
            val jsonStr = prefs.getString("missed_map", "{}") ?: "{}"
            val map = org.json.JSONObject(jsonStr)
            val item = org.json.JSONObject().apply {
                put("time", timestamp)
                put("notified", false)
            }
            map.put(phone, item)
            prefs.edit().putString("missed_map", map.toString()).apply()
            Log.d(TAG, "Yerel cevapsiz cagri kaydedildi: $phone")
        } catch (e: Exception) {
            Log.e(TAG, "saveLocalMissedCall hatasi: ${e.message}")
        }
    }

    private fun removeLocalMissedCall(phone: String) {
        if (phone.isBlank()) return
        try {
            val prefs = getSharedPreferences("calltracker_offline_missed", MODE_PRIVATE)
            val jsonStr = prefs.getString("missed_map", "{}") ?: "{}"
            val map = org.json.JSONObject(jsonStr)
            if (map.has(phone)) {
                map.remove(phone)
                prefs.edit().putString("missed_map", map.toString()).apply()
                Log.d(TAG, "Yerel cevapsiz cagri kaldirildi: $phone")
            }
        } catch (e: Exception) {
            Log.e(TAG, "removeLocalMissedCall hatasi: ${e.message}")
        }
    }

    private fun checkLocalOfflineMissedCalls() {
        try {
            val prefs = getSharedPreferences("calltracker_offline_missed", MODE_PRIVATE)
            val jsonStr = prefs.getString("missed_map", "{}") ?: "{}"
            val map = org.json.JSONObject(jsonStr)
            val now = System.currentTimeMillis()
            val oneHourMs = 60 * 60 * 1000L
            val twoDaysMs = 48 * 60 * 60 * 1000L
            var changed = false

            val keys = map.keys()
            val keysToRemove = mutableListOf<String>()

            while (keys.hasNext()) {
                val phone = keys.next()
                val item = map.getJSONObject(phone)
                val time = item.optLong("time", 0L)
                val notified = item.optBoolean("notified", false)
                val elapsed = now - time

                if (elapsed > twoDaysMs) {
                    keysToRemove.add(phone)
                    changed = true
                } else if (elapsed >= oneHourMs && !notified) {
                    val hours = (elapsed / (60 * 60 * 1000L)).toInt()
                    val elapsedText = if (hours <= 1) "1 saattir" else "$hours saattir"
                    NotificationHelper.showUnreturnedCallNotification(
                        context = this,
                        phoneNumber = phone,
                        callerName = null,
                        elapsedText = elapsedText,
                        customMessage = "Bu numaraya $elapsedText dönüş yapılmadı: $phone"
                    )
                    item.put("notified", true)
                    changed = true
                }
            }

            for (k in keysToRemove) {
                map.remove(k)
            }

            if (changed) {
                prefs.edit().putString("missed_map", map.toString()).apply()
            }
        } catch (e: Exception) {
            Log.e(TAG, "checkLocalOfflineMissedCalls hatasi: ${e.message}")
        }
    }
}

