package com.mehmetdogru.calltracker

import android.content.Context
import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

object ApiService {

    private const val TAG = "ApiService"

    /** CT-001: Cihaz anahtari basligi — API tarafindaki DeviceAuthAttribute ile ayni olmali. */
    private const val HEADER_DEVICE_KEY = "X-Device-Key"
    private const val HEADER_APP_VERSION = "X-App-Version"

    // Kuyruk endpoint adlari — sunucudaki rota adlariyla birebir.
    private const val EP_INCOMING = "incoming"
    private const val EP_END = "end"
    private const val EP_OUTGOING = "outgoing"

    private const val BATCH_SIZE = 20
    private const val MAX_DRAIN_ROUNDS = 25   // tur basina en fazla 500 kayit

    /** Ust uste bu kadar sunucu hatasindan sonra bosaltma turu sonlandirilir. */
    private const val ARDISIK_HATA_SINIRI = 3

    /** CT-004: Kuyruk bosaltma sirasindaki wake lock ust siniri. */
    private const val WAKELOCK_TIMEOUT_MS = 60_000L

    @Volatile
    private var appContext: Context? = null

    /** Ayni anda birden fazla kuyruk bosaltma calismasin. */
    private val draining = AtomicBoolean(false)

    /**
     * CT-001: Cihaz anahtarini TUM isteklere otomatik ekleyen interceptor.
     * Merkezi tutuldu; boylece ileride eklenen bir endpoint kimlik dogrulamasiz kalamaz.
     */
    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val builder = chain.request().newBuilder()
                appContext?.let { ctx ->
                    SecureStore.getDeviceToken(ctx)?.let { token ->
                        builder.header(HEADER_DEVICE_KEY, token)
                    }
                    builder.header(HEADER_APP_VERSION, BuildConfig.VERSION_NAME)
                }
                chain.proceed(builder.build())
            }
            .build()
    }

    private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

    private fun ensureContext(context: Context) {
        if (appContext == null) appContext = context.applicationContext
    }

    private fun getApiUrl(context: Context): String {
        ensureContext(context)
        val prefs = context.getSharedPreferences("calltracker", Context.MODE_PRIVATE)
        return prefs.getString("api_url", "https://bayi.mehmetdogrultd.com") ?: "https://bayi.mehmetdogrultd.com"
    }

    // ══════════════════════════════════════════════════════════════════════════
    // CT-002: Dayanikli gonderim
    //
    // Sozlesme: cagri ONCE kuyruga yazilir, SONRA gonderilmeye calisilir.
    // Surec oldurulse, ag kopsa veya API kapali olsa bile kayit kaybolmaz.
    // ══════════════════════════════════════════════════════════════════════════

    private fun enqueueAndDrain(context: Context, endpoint: String, json: JSONObject) {
        ensureContext(context)
        val eventId = CallQueue.newEventId()
        json.put("eventId", eventId)   // CT-009: sunucu tarafinda tekrar kaydini engeller

        val queued = CallQueue.get(context).enqueue(endpoint, json.toString(), eventId)
        if (queued < 0) {
            // Kuyruga yazilamadi (disk dolu vb.) — en azindan dogrudan gondermeyi dene.
            Log.e(TAG, "Kuyruga yazilamadi, dogrudan gonderim deneniyor: $endpoint")
            Thread { postDirect(context, endpoint, json.toString()) }.start()
            return
        }
        drainQueue(context)
    }

    /**
     * Kuyrugu bosaltir. Ag geri geldiginde, servis basladiginda ve her yeni
     * cagri sonrasinda cagrilir. Es zamanli calismalar engellenir.
     */
    fun drainQueue(context: Context) {
        ensureContext(context)
        if (!draining.compareAndSet(false, true)) return

        Thread {
            // CT-004: Kalici wake lock kaldirildi; bunun yerine SADECE ag gonderimi
            // suresince ve ZAMAN ASIMLI kisa bir wake lock aliniyor.
            // Amac: cihaz gonderim ortasinda uykuya dalip soketi askida birakmasin.
            // Zaman asimi, bir hata durumunda kilidin sonsuza kadar tutulmasini engeller.
            val wakeLock = try {
                val pm = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
                pm.newWakeLock(
                    android.os.PowerManager.PARTIAL_WAKE_LOCK,
                    "DogruCallTracker::QueueDrain"
                ).apply {
                    setReferenceCounted(false)
                    acquire(WAKELOCK_TIMEOUT_MS)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Wake lock alinamadi, gonderim yine de denenecek: ${e.message}")
                null
            }

            try {
                val queue = CallQueue.get(context)
                var processed = 0
                // Tek bir kayit yuzunden kuyrugu kilitlememek, ama sunucu tamamen
                // kapaliyken de bosuna dovmemek icin ardisik hata sayaci.
                var ustUsteHata = 0
                // Guvenlik siniri: markFailed veritabani hatasi nedeniyle next_attempt_at'i
                // ilerletemezse ayni kayitlar sonsuz donguye girip API'yi doverdi.
                // Tur basina en fazla MAX_DRAIN_ROUNDS grup islenir.
                var round = 0
                while (round < MAX_DRAIN_ROUNDS) {
                    round++
                    val batch = queue.dequeueReady(limit = BATCH_SIZE)
                    if (batch.isEmpty()) break

                    for (item in batch) {
                        val ok = postDirect(context, item.endpoint, item.payload)
                        if (ok.first) {
                            queue.markSent(item.id)
                            processed++
                            ustUsteHata = 0
                        } else {
                            queue.markFailed(item.id, ok.second)

                            // Baglanti yok / yetkisiz: hicbir kayit gecmeyecek, turu bitir.
                            if (ok.third) return@Thread

                            // Sunucu tarafi hatasi: bu kayit gecmedi ama digerleri gecebilir.
                            // Yine de sunucu tamamen kapaliysa bosuna denemeyelim —
                            // ust uste bu kadar hatadan sonra tur sonlanir.
                            ustUsteHata++
                            if (ustUsteHata >= ARDISIK_HATA_SINIRI) {
                                Log.w(TAG, "Ust uste $ustUsteHata hata — bosaltma turu sonlandirildi")
                                return@Thread
                            }
                        }
                    }
                    if (batch.size < BATCH_SIZE) break
                }
                if (processed > 0) Log.d(TAG, "Kuyruktan $processed kayit gonderildi")
            } catch (e: Exception) {
                Log.e(TAG, "drainQueue hatasi: ${e.message}")
            } finally {
                // CT-004: Kilit HER durumda birakilir (erken return dahil).
                try { if (wakeLock?.isHeld == true) wakeLock.release() } catch (_: Exception) {}
                draining.set(false)
            }
        }.start()
    }

    /**
     * Tek bir istegi gonderir.
     * @return (basarili, hataMesaji, agSorunuMu)
     *   agSorunuMu = true ise kuyrugun geri kalanini denemenin anlami yok.
     */
    private fun postDirect(context: Context, endpoint: String, payload: String): Triple<Boolean, String, Boolean> {
        return try {
            val request = Request.Builder()
                .url("${getApiUrl(context)}/api/Calls/$endpoint")
                .post(payload.toRequestBody(JSON_MEDIA))
                .build()

            client.newCall(request).execute().use { response ->
                val code = response.code
                when {
                    // 2xx: basarili. Sunucu "Duplicate" dese bile basarili sayilir —
                    // CT-009 sayesinde tekrar kaydi olusmaz, kayit kuyruktan silinmelidir.
                    code in 200..299 -> Triple(true, "", false)

                    // 401: cihaz anahtari gecersiz. Tekrar denemek anlamsiz ama
                    // kaydi ATMIYORUZ — anahtar duzeltilince gonderilsin.
                    code == 401 -> {
                        showDebugNotification(context,
                            "🔑 CİHAZ ANAHTARI GEÇERSİZ (401)\n" +
                            "Çağrılar kuyrukta bekliyor. Uygulamayı açıp anahtarı doğrulayın.")
                        Triple(false, "401 yetkisiz", true)
                    }

                    // 403: Cihaz CRM panelinden PASİFE ALINDI!
                    code == 403 -> {
                        context.getSharedPreferences("calltracker", Context.MODE_PRIVATE)
                            .edit()
                            .putBoolean("is_device_passive", true)
                            .apply()
                        showPassiveNotification(context,
                            "⚠️ DİKKAT: CİHAZ CRM'DEN PASİFE ALINDI!\n" +
                            "Yönetim paneli bu telefonu pasife aldı. Aramalar CRM'e aktarılamıyor.")
                        Triple(false, "403 cihaz pasif", true)
                    }

                    // 4xx (401 disi): istek hatali, tekrar denemek duzeltmez.
                    // Yine de kuyrukta birakiyoruz ki veri sessizce kaybolmasin.
                    code in 400..499 -> Triple(false, "HTTP $code", false)

                    // 5xx: sunucu bu KAYDI isleyemedi.
                    //
                    // Onceden burasi "turu sonlandir" (true) donuyordu ve tek bir
                    // zehirli kayit TUM KUYRUGU kilitliyordu: sunucudaki cagri-bitis
                    // SQL'i her seferinde 500 verdigi icin bosaltma her turda o
                    // kayitta duruyor, ARKASINDAKI yeni cagrilar hic gonderilmiyordu.
                    // Belirti: "ilk arama geldi, sonrakiler gelmedi".
                    //
                    // Artik kayit basarisiz isaretlenir ve tura DEVAM edilir.
                    // Sunucu gercekten kapaliysa ust ust gelen hatalar drainQueue
                    // icindeki sayaci tetikler ve tur orada sonlanir.
                    else -> Triple(false, "HTTP $code", false)
                }
            }
        } catch (e: Exception) {
            // Baglanti yok / timeout — kuyrukta kalsin, backoff ile tekrar denenir.
            Triple(false, e.javaClass.simpleName, true)
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Genel API — cagri olaylari
    // ══════════════════════════════════════════════════════════════════════════

    /** Gelen arama. POST /api/Calls/incoming */
    fun sendIncomingCall(context: Context, phoneNumber: String, callType: String, callerName: String? = null) {
        showDebugNotification(context, "📞 Çağrı algılandı: ${callerName ?: phoneNumber}")
        val json = JSONObject().apply {
            put("phoneNumber", phoneNumber)
            put("callType", callType)
            if (callerName != null) put("callerName", callerName)
        }
        enqueueAndDrain(context, EP_INCOMING, json)
    }

    /**
     * Cagri cevaplandi sinyali (durationSeconds = -1).
     *
     * CT-002 notu: Bu KUYRUGA ALINMAZ. "Cevaplaniyor" anlik bir arayuz sinyalidir;
     * saatler sonra teslim edilmesi masaustunde yanlis sayac baslatir ve
     * cagri kaydini bozar. Sadece en iyi caba (best-effort) ile gonderilir.
     */
    fun sendCallAnswered(context: Context, phoneNumber: String, callType: String, callerName: String? = null) {
        ensureContext(context)
        val json = JSONObject().apply {
            put("phoneNumber", phoneNumber)
            put("durationSeconds", -1)
            put("callType", callType)
            if (callerName != null) put("callerName", callerName)
        }
        Thread {
            val r = postDirect(context, EP_END, json.toString())
            Log.d(TAG, "Cevaplaniyor sinyali (kuyruksuz): ${if (r.first) "OK" else r.second}")
        }.start()
    }

    /** Gelen cagri bitisi. POST /api/Calls/end */
    fun sendCallEnded(context: Context, phoneNumber: String, durationSeconds: Int, callType: String, callerName: String? = null) {
        val json = JSONObject().apply {
            put("phoneNumber", phoneNumber)
            put("durationSeconds", durationSeconds)
            put("callType", callType)
            if (callerName != null) put("callerName", callerName)
        }
        enqueueAndDrain(context, EP_END, json)
    }

    /** Giden cagri. POST /api/Calls/outgoing */
    fun sendOutgoingCall(context: Context, phoneNumber: String, durationSeconds: Int, callType: String, callerName: String? = null) {
        val json = JSONObject().apply {
            put("phoneNumber", phoneNumber)
            put("durationSeconds", durationSeconds)
            put("callType", callType)
            if (callerName != null) put("callerName", callerName)
        }
        enqueueAndDrain(context, EP_OUTGOING, json)
    }

    /** Cevapsiz cagri. POST /api/Calls/incoming (status=missed) */
    fun sendMissedCall(context: Context, phoneNumber: String, callType: String, callerName: String? = null) {
        val json = JSONObject().apply {
            put("phoneNumber", phoneNumber)
            put("callType", callType)
            put("status", "missed")
            if (callerName != null) put("callerName", callerName)
        }
        enqueueAndDrain(context, EP_INCOMING, json)
    }

    /**
     * CT-001: Cihaz anahtarinin gecerliligini sunucuya sorar.
     * MainActivity "Anahtari Dogrula" akisi.
     */
    fun verifyDeviceToken(context: Context, onResult: (ok: Boolean, message: String) -> Unit) {
        ensureContext(context)
        Thread {
            val result = try {
                if (!SecureStore.hasDeviceToken(context)) {
                    false to "Cihaz anahtari girilmemis."
                } else {
                    val request = Request.Builder()
                        .url("${getApiUrl(context)}/api/cihaz-token/dogrula")
                        .get()
                        .build()
                    client.newCall(request).execute().use { response ->
                        when (response.code) {
                            in 200..299 -> {
                                context.getSharedPreferences("calltracker", Context.MODE_PRIVATE)
                                    .edit().putBoolean("is_device_passive", false).apply()
                                true to "Cihaz anahtarı geçerli ve aktif."
                            }
                            403 -> {
                                context.getSharedPreferences("calltracker", Context.MODE_PRIVATE)
                                    .edit().putBoolean("is_device_passive", true).apply()
                                showPassiveNotification(context, "⚠️ DİKKAT: Bu cihaz CRM panelinden PASİFE ALINMIŞTIR! Çağrı takibi durduruldu.")
                                false to "⚠️ DİKKAT: Cihaz CRM panelinden PASİFE ALINDI!"
                            }
                            401 -> false to "Cihaz anahtarı geçersiz veya iptal edilmiş."
                            else -> false to "Sunucu yanıt kodu: ${response.code}"
                        }
                    }
                }
            } catch (e: Exception) {
                false to "Baglanti hatasi: ${e.javaClass.simpleName}"
            }
            // Anahtar gecerliyse bekleyen kayitlari hemen gondermeyi dene.
            if (result.first) drainQueue(context)
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                onResult(result.first, result.second)
            }
        }.start()
    }

    /** Telefon ekraninda kisa durum bildirimi. */

    /** Cihaz pasife alindiginda yuksek oncelikli sesli ve titresimli uyari bildirimi. */
    fun showPassiveNotification(context: Context, message: String) {
        try {
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager

            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                val channel = android.app.NotificationChannel(
                    "device_passive_alerts", "Cihaz Pasif / Güvenlik Uyarıları",
                    android.app.NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "Cihazın CRM'den pasife alınması durumunda verilen acil uyarılar"
                    enableVibration(true)
                    enableLights(true)
                }
                notificationManager.createNotificationChannel(channel)
            }

            val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
            val pendingIntent = android.app.PendingIntent.getActivity(
                context, 0, launchIntent,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
            )

            val notification = androidx.core.app.NotificationCompat.Builder(context, "device_passive_alerts")
                .setContentTitle("🔴 DİKKAT: CİHAZ PASİFE ALINDI!")
                .setContentText(message)
                .setSmallIcon(android.R.drawable.stat_notify_error)
                .setPriority(androidx.core.app.NotificationCompat.PRIORITY_HIGH)
                .setCategory(androidx.core.app.NotificationCompat.CATEGORY_ALARM)
                .setDefaults(androidx.core.app.NotificationCompat.DEFAULT_ALL)
                .setAutoCancel(false)
                .setColor(0xFFEF4444.toInt())
                .setContentIntent(pendingIntent)
                .setStyle(androidx.core.app.NotificationCompat.BigTextStyle().bigText(message))
                .build()

            notificationManager.notify(3001, notification)
        } catch (e: Exception) {
            Log.e(TAG, "Pasif bildirim hatası: ${e.message}")
        }
    }

    private fun showDebugNotification(context: Context, message: String) {
        try {
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager

            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                val channel = android.app.NotificationChannel(
                    "api_debug", "API Durum",
                    android.app.NotificationManager.IMPORTANCE_LOW
                ).apply { description = "Cagri gonderim durumu" }
                notificationManager.createNotificationChannel(channel)
            }

            val notification = androidx.core.app.NotificationCompat.Builder(context, "api_debug")
                .setContentTitle("Doğru CRM")
                .setContentText(message)
                .setSmallIcon(android.R.drawable.ic_menu_call)
                .setPriority(androidx.core.app.NotificationCompat.PRIORITY_LOW)
                .setAutoCancel(true)
                .setStyle(androidx.core.app.NotificationCompat.BigTextStyle().bigText(message))
                .build()

            notificationManager.notify(2001, notification)
        } catch (e: Exception) {
            Log.e(TAG, "Bildirim hatasi: ${e.message}")
        }
    }
}
