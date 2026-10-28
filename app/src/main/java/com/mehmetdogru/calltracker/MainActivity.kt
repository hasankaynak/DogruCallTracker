package com.mehmetdogru.calltracker

import android.Manifest
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

class MainActivity : AppCompatActivity() {
    private val TAG = "MainActivity"

    private lateinit var txtStatus: TextView
    private lateinit var txtLastCall: TextView
    private lateinit var txtCallCount: TextView
    private lateinit var txtBatteryStatus: TextView
    private lateinit var btnToggle: Button

    // Cihaz Yöneticisi & Pasif Uyarı Bileşenleri
    private lateinit var dpm: DevicePolicyManager
    private lateinit var adminComponent: ComponentName
    private lateinit var txtAdminStatus: TextView
    private lateinit var btnAdmin: Button
    private lateinit var cardPassiveAlert: LinearLayout

    private val PERMISSIONS_REQUEST = 1001
    private val BATTERY_OPTIMIZATION_REQUEST = 1002
    private val ADMIN_REQUEST_CODE = 1003

    private val requiredPermissions = mutableListOf(
        Manifest.permission.READ_PHONE_STATE,
        Manifest.permission.READ_CALL_LOG,
        Manifest.permission.CAMERA
    ).apply {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // ═══ QR Kod Tarama Launcher'ı ═══
    private val qrScanLauncher = registerForActivityResult(ScanContract()) { result ->
        if (result.contents != null) {
            handleScannedQr(result.contents)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        txtStatus = findViewById(R.id.txtStatus)
        txtLastCall = findViewById(R.id.txtLastCall)
        txtCallCount = findViewById(R.id.txtCallCount)
        txtBatteryStatus = findViewById(R.id.txtBatteryStatus)
        btnToggle = findViewById(R.id.btnToggle)

        dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        adminComponent = ComponentName(this, CallTrackerAdminReceiver::class.java)

        txtAdminStatus = findViewById(R.id.txtAdminStatus)
        btnAdmin = findViewById(R.id.btnAdmin)
        cardPassiveAlert = findViewById(R.id.cardPassiveAlert)
        // ── 🛡️ Silinme Korumasını Otomatik Kaldır (Kullanıcı İsteği) ──
        try {
            if (dpm.isAdminActive(adminComponent)) {
                dpm.removeActiveAdmin(adminComponent)
                Toast.makeText(this, "🔓 Kaldırma koruması devre dışı bırakıldı.", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Admin kaldırılamadı: ${e.message}")
        }

        // API URL
        val prefs = getSharedPreferences("calltracker", MODE_PRIVATE)
        val apiUrl = prefs.getString("api_url", "https://bayi.mehmetdogrultd.com") ?: "https://bayi.mehmetdogrultd.com"
        findViewById<TextView>(R.id.txtApiUrl).text = apiUrl

        btnToggle.setOnClickListener {
            if (isServiceRunning()) {
                stopCallService()
            } else {
                checkPermissionsAndStart()
            }
        }

        // ── 📷 QR Kod Tarama Butonu ──
        findViewById<Button>(R.id.btnScanQr).setOnClickListener {
            val options = ScanOptions().apply {
                setPrompt("CRM ekranındaki Cihaz QR kodunu kameraya hizalayın")
                setBeepEnabled(true)
                setOrientationLocked(false)
                setBarcodeImageEnabled(false)
                setDesiredBarcodeFormats(ScanOptions.QR_CODE)
            }
            qrScanLauncher.launch(options)
        }

        // ── 🛡️ Silinmeye Karşı Koruma (Cihaz Yöneticisi) ──
        btnAdmin.setOnClickListener {
            if (dpm.isAdminActive(adminComponent)) {
                try {
                    dpm.removeActiveAdmin(adminComponent)
                    Toast.makeText(this, "🔓 Kaldırma koruması iptal edildi. Artık silebilirsiniz.", Toast.LENGTH_LONG).show()
                    updateUI()
                } catch (e: Exception) {
                    Toast.makeText(this, "Hata: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            } else {
                try {
                    val intent = Intent(Intent.ACTION_DELETE).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                } catch (e: Exception) {
                    Toast.makeText(this, "Koruma kapalı. Ayarlar > Uygulamalar'dan kaldırabilirsiniz.", Toast.LENGTH_SHORT).show()
                }
            }
        }

        // API URL kaydet
        findViewById<Button>(R.id.btnSaveUrl).setOnClickListener {
            val url = findViewById<EditText>(R.id.editApiUrl).text.toString().trim()
            if (url.isNotEmpty()) {
                prefs.edit().putString("api_url", url).apply()
                findViewById<TextView>(R.id.txtApiUrl).text = url
                Toast.makeText(this, "✅ API URL kaydedildi", Toast.LENGTH_SHORT).show()
            }
        }

        // ── CT-001: Cihaz anahtari yonetimi ──
        refreshDeviceTokenStatus()

        findViewById<Button>(R.id.btnSaveDeviceToken).setOnClickListener {
            val field = findViewById<EditText>(R.id.editDeviceToken)
            val token = field.text.toString().trim()
            if (token.isEmpty()) {
                Toast.makeText(this, "Cihaz anahtarı boş olamaz", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            SecureStore.setDeviceToken(this, token)
            field.setText("")
            refreshDeviceTokenStatus()
            Toast.makeText(this, "🔐 Cihaz anahtarı güvenli olarak kaydedildi", Toast.LENGTH_SHORT).show()
        }

        findViewById<Button>(R.id.btnVerifyDeviceToken).setOnClickListener {
            val status = findViewById<TextView>(R.id.txtDeviceTokenStatus)
            status.text = "⏳ Doğrulanıyor…"
            status.setTextColor(0xFF94A3B8.toInt())
            ApiService.verifyDeviceToken(this) { ok, message ->
                if (ok) {
                    status.text = "🟢 Geçerli — ${SecureStore.maskedDeviceToken(this)}"
                    status.setTextColor(0xFF34D399.toInt())
                } else {
                    status.text = "🔴 $message"
                    status.setTextColor(0xFFF87171.toInt())
                }
                updateUI()
            }
        }

        // Pil optimizasyonu devre dışı bırak butonu
        findViewById<Button>(R.id.btnBattery).setOnClickListener {
            requestBatteryOptimizationExemption()
        }

        // API Test butonu
        findViewById<Button>(R.id.btnTestApi).setOnClickListener {
            testApiConnection()
        }

        // ── GitHub Releases Güncelleme Entegrasyonu ──
        findViewById<TextView>(R.id.txtAppVersionInfo).text = "Sürüm: v${BuildConfig.VERSION_NAME} (GitHub Entegre)"
        findViewById<TextView>(R.id.txtFooterVersion).text = "Doğru CRM Çağrı Takip v${BuildConfig.VERSION_NAME}"

        findViewById<Button>(R.id.btnCheckUpdate).setOnClickListener {
            val updateStatus = findViewById<TextView>(R.id.txtUpdateStatus)
            updateStatus.text = "⏳ GitHub Releases kontrol ediliyor..."
            updateStatus.setTextColor(0xFF94A3B8.toInt())
            UpdateManager.checkForUpdates(this, isManual = true) { hasUpdate ->
                if (!hasUpdate) {
                    updateStatus.text = "✅ Sürümünüz güncel (v${BuildConfig.VERSION_NAME})"
                    updateStatus.setTextColor(0xFF34D399.toInt())
                } else {
                    updateStatus.text = "🚀 Yeni sürüm mevcut! Kurulum penceresi açıldı."
                    updateStatus.setTextColor(0xFF60A5FA.toInt())
                }
            }
        }

        // Açılışta sessiz GitHub güncelleme kontrolü
        UpdateManager.checkForUpdates(this, isManual = false)

        updateUI()

        // İlk açılışta izinler varsa otomatik başlat
        if (hasAllPermissions()) {
            startCallService()
            if (!isBatteryOptimizationIgnored()) {
                showBatteryOptimizationDialog()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        updateUI()
        refreshQueueStatus()
        ApiService.drainQueue(this)
    }

    /**
     * QR Kod Okutulduğunda Otomatik Yapılandırma
     */
    private fun handleScannedQr(contents: String) {
        try {
            var apiUrl: String? = null
            var deviceKey: String? = null

            val trimmed = contents.trim()
            if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
                val json = JSONObject(trimmed)
                apiUrl = json.optString("api_url", json.optString("apiUrl", ""))
                deviceKey = json.optString("device_key", json.optString("deviceKey", ""))
            } else if (trimmed.contains("|")) {
                val parts = trimmed.split("|")
                if (parts.size >= 2) {
                    apiUrl = parts[0].trim()
                    deviceKey = parts[1].trim()
                }
            } else if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
                val uri = Uri.parse(trimmed)
                apiUrl = "${uri.scheme}://${uri.host}${if (uri.port != -1 && uri.port != 80 && uri.port != 443) ":${uri.port}" else ""}"
                deviceKey = uri.getQueryParameter("key") ?: uri.getQueryParameter("device_key")
            }

            if (deviceKey.isNullOrBlank()) {
                Toast.makeText(this, "❌ Geçersiz QR kod! Cihaz anahtarı bulunamadı.", Toast.LENGTH_LONG).show()
                return
            }

            val prefs = getSharedPreferences("calltracker", MODE_PRIVATE)
            if (!apiUrl.isNullOrBlank()) {
                prefs.edit().putString("api_url", apiUrl).apply()
                findViewById<TextView>(R.id.txtApiUrl).text = apiUrl
            }

            SecureStore.setDeviceToken(this, deviceKey)
            refreshDeviceTokenStatus()

            val statusView = findViewById<TextView>(R.id.txtDeviceTokenStatus)
            statusView.text = "⏳ QR Kod tanımlandı, anahtar doğrulanıyor..."
            statusView.setTextColor(0xFF38BDF8.toInt())

            Toast.makeText(this, "✅ QR Kod okundu! Sunucuyla doğrulanıyor...", Toast.LENGTH_SHORT).show()

            ApiService.verifyDeviceToken(this) { ok, message ->
                if (ok) {
                    statusView.text = "🟢 Doğrulandı — ${SecureStore.maskedDeviceToken(this)}"
                    statusView.setTextColor(0xFF34D399.toInt())
                    Toast.makeText(this, "🎉 Harika! Cihaz başarıyla eşleştirildi ve aktif edildi.", Toast.LENGTH_LONG).show()
                    if (hasAllPermissions()) {
                        startCallService()
                    } else {
                        checkPermissionsAndStart()
                    }
                } else {
                    statusView.text = "🔴 $message"
                    statusView.setTextColor(0xFFF87171.toInt())
                    Toast.makeText(this, "⚠️ $message", Toast.LENGTH_LONG).show()
                }
                updateUI()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "❌ QR Okuma Hatası: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun checkPermissionsAndStart() {
        val missing = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            startCallService()
            if (!isBatteryOptimizationIgnored()) {
                showBatteryOptimizationDialog()
            }
        } else {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), PERMISSIONS_REQUEST)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSIONS_REQUEST) {
            if (grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                startCallService()
                if (!isBatteryOptimizationIgnored()) {
                    showBatteryOptimizationDialog()
                }
            } else {
                Toast.makeText(this, "⚠️ Çağrı tespiti ve QR tarama için izinler gereklidir", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun startCallService() {
        try { stopService(Intent(this, CallDetectorService::class.java)) } catch (_: Exception) {}

        val intent = Intent(this, CallDetectorService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }

        getSharedPreferences("calltracker", MODE_PRIVATE)
            .edit()
            .putBoolean("service_enabled", true)
            .putBoolean("service_running", true)
            .apply()

        updateUI()
        Toast.makeText(this, "🟢 Çağrı takip servisi başlatıldı", Toast.LENGTH_SHORT).show()
    }

    private fun stopCallService() {
        stopService(Intent(this, CallDetectorService::class.java))

        getSharedPreferences("calltracker", MODE_PRIVATE)
            .edit()
            .putBoolean("service_enabled", false)
            .putBoolean("service_running", false)
            .apply()

        updateUI()
        Toast.makeText(this, "🔴 Servis durduruldu", Toast.LENGTH_SHORT).show()
    }

    private fun refreshQueueStatus() {
        val view = findViewById<TextView>(R.id.txtQueueStatus) ?: return
        Thread {
            val q = CallQueue.get(this)
            val pending = q.pendingCount()
            val abandoned = q.abandonedCount()
            runOnUiThread {
                when {
                    abandoned > 0 -> {
                        view.text = "⚠️ Kuyruk: $pending bekleyen, $abandoned gönderilemedi"
                        view.setTextColor(0xFFF87171.toInt())
                    }
                    pending > 0 -> {
                        view.text = "⏳ Kuyruk: $pending çağrı gönderilmeyi bekliyor"
                        view.setTextColor(0xFFFBBF24.toInt())
                    }
                    else -> {
                        view.text = "✅ Kuyruk boş — tüm çağrılar iletildi"
                        view.setTextColor(0xFF34D399.toInt())
                    }
                }
            }
        }.start()
    }

    private fun refreshDeviceTokenStatus() {
        val status = findViewById<TextView>(R.id.txtDeviceTokenStatus)
        if (SecureStore.hasDeviceToken(this)) {
            val isPassive = getSharedPreferences("calltracker", MODE_PRIVATE).getBoolean("is_device_passive", false)
            if (isPassive) {
                status.text = "🔴 PASİFE ALINDI — ${SecureStore.maskedDeviceToken(this)}"
                status.setTextColor(0xFFF87171.toInt())
            } else {
                status.text = "🟢 Tanımlı — ${SecureStore.maskedDeviceToken(this)}"
                status.setTextColor(0xFF34D399.toInt())
            }
        } else {
            status.text = "🔴 Tanımlı değil — QR okutun veya anahtar girin"
            status.setTextColor(0xFFF87171.toInt())
        }
    }

    private fun isBatteryOptimizationIgnored(): Boolean {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        return powerManager.isIgnoringBatteryOptimizations(packageName)
    }

    private fun showBatteryOptimizationDialog() {
        AlertDialog.Builder(this)
            .setTitle("⚡ Pil Optimizasyonu")
            .setMessage(
                "Arka planda kesintisiz çalışması için pil optimizasyonunu devre dışı bırakmanız gerekiyor.\n\n" +
                "Açılacak ekranda '${getString(R.string.app_name)}' uygulamasını bulun ve 'Kısıtlama' seçeneğini kapatın."
            )
            .setPositiveButton("Ayarlara Git") { _, _ ->
                requestBatteryOptimizationExemption()
            }
            .setNegativeButton("Sonra") { dialog, _ -> dialog.dismiss() }
            .show()
    }

    private fun requestBatteryOptimizationExemption() {
        try {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            }
            startActivityForResult(intent, BATTERY_OPTIMIZATION_REQUEST)
        } catch (e: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (_: Exception) {
                Toast.makeText(this, "Ayarlar → Pil → Pil Optimizasyonu'ndan kapatın", Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == BATTERY_OPTIMIZATION_REQUEST || requestCode == ADMIN_REQUEST_CODE) {
            updateUI()
        }
    }

    private fun testApiConnection() {
        val txtResult = findViewById<TextView>(R.id.txtTestResult)
        txtResult.visibility = android.view.View.VISIBLE
        txtResult.text = "⏳ API'ye bağlanılıyor..."
        txtResult.setTextColor(0xFFF59E0B.toInt())

        val prefs = getSharedPreferences("calltracker", MODE_PRIVATE)
        val apiUrl = prefs.getString("api_url", "https://bayi.mehmetdogrultd.com") ?: "https://bayi.mehmetdogrultd.com"

        Thread {
            try {
                val client = okhttp3.OkHttpClient.Builder()
                    .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                    .readTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                    .build()

                val json = JSONObject().apply {
                    put("phoneNumber", "TEST-API-CHECK")
                    put("callType", "TEST")
                }
                val body = json.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
                val request = okhttp3.Request.Builder()
                    .url("$apiUrl/api/Calls/incoming")
                    .post(body)
                    .build()

                val response = client.newCall(request).execute()
                val responseBody = response.body?.string() ?: ""
                val statusCode = response.code
                response.close()

                runOnUiThread {
                    when {
                        statusCode in 200..299 -> {
                            txtResult.text = "✅ API BAĞLANTI BAŞARILI!\n\nURL: $apiUrl\nStatus: $statusCode"
                            txtResult.setTextColor(0xFF059669.toInt())
                        }
                        statusCode == 403 -> {
                            txtResult.text = "🔴 DİKKAT: CİHAZ CRM'DEN PASİFE ALINMIŞTIR!\n\nURL: $apiUrl\nCihaz durumu pasiftir, aramalar kabul edilmez."
                            txtResult.setTextColor(0xFFDC2626.toInt())
                        }
                        statusCode == 401 -> {
                            val anahtarVar = SecureStore.hasDeviceToken(this@MainActivity)
                            txtResult.text = if (anahtarVar) {
                                "🔑 SUNUCUYA ULAŞILDI — ANAHTAR GEÇERSİZ\n\nURL: $apiUrl\nKayıtlı anahtar sunucuda bulunamadı. Lütfen QR kodu tekrar okutun."
                            } else {
                                "🔑 SUNUCUYA ULAŞILDI — CİHAZ ANAHTARI YOK\n\nURL: $apiUrl\nLütfen yukarıdaki 'QR Kod Tara' butonu ile cihazınızı eşleştirin."
                            }
                            txtResult.setTextColor(0xFF3B82F6.toInt())
                        }
                        else -> {
                            txtResult.text = "⚠️ API YANIT VERDİ AMA HATA!\n\nURL: $apiUrl\nStatus: $statusCode\nYanıt: ${responseBody.take(200)}"
                            txtResult.setTextColor(0xFFF59E0B.toInt())
                        }
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    txtResult.text = "❌ BAĞLANTI HATASI!\n\nSunucu: $apiUrl\nDetay: ${e.message}"
                    txtResult.setTextColor(0xFFDC2626.toInt())
                }
            }
        }.start()
    }

    private fun isServiceRunning(): Boolean {
        val prefs = getSharedPreferences("calltracker", MODE_PRIVATE)
        return prefs.getBoolean("service_running", false)
    }

    private fun hasAllPermissions(): Boolean {
        return requiredPermissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun updateUI() {
        val prefs = getSharedPreferences("calltracker", MODE_PRIVATE)
        val running = prefs.getBoolean("service_running", false)
        val lastCall = prefs.getString("last_call", "Henüz çağrı algılanmadı")
        val callCount = prefs.getInt("call_count", 0)
        val batteryIgnored = isBatteryOptimizationIgnored()

        txtStatus.text = if (running) "🟢 AKTİF — Çağrılar dinleniyor" else "🔴 DURDURULDU"
        txtStatus.setTextColor(if (running) 0xFF059669.toInt() else 0xFFDC2626.toInt())
        txtLastCall.text = lastCall
        txtCallCount.text = "Toplam: $callCount çağrı algılandı"
        btnToggle.text = if (running) "⏹ Servisi Durdur" else "▶ Servisi Başlat"

        // Pil durumu
        txtBatteryStatus.text = if (batteryIgnored) {
            "✅ Pil optimizasyonu devre dışı — Arka planda çalışacak"
        } else {
            "⚠️ Pil optimizasyonu aktif — Servis durdurulabilir!"
        }
        txtBatteryStatus.setTextColor(if (batteryIgnored) 0xFF059669.toInt() else 0xFFF59E0B.toInt())

        // Cihaz Yöneticisi Koruması
        val isAdmin = dpm.isAdminActive(adminComponent)
        if (isAdmin) {
            txtAdminStatus.text = "⚠️ Koruma Aktif — İptal etmek için butona dokunun"
            txtAdminStatus.setTextColor(0xFFDC2626.toInt())
            btnAdmin.text = "🔓 Korumayı İptal Et (Silmeye İzin Ver)"
            btnAdmin.backgroundTintList = android.content.res.ColorStateList.valueOf(0xFFDC2626.toInt())
        } else {
            txtAdminStatus.text = "✅ Kaldırma Koruması Kapalı (Uygulama silinebilir)"
            txtAdminStatus.setTextColor(0xFF059669.toInt())
            btnAdmin.text = "🗑️ Bu Uygulamayı Telefondan Kaldır"
            btnAdmin.backgroundTintList = android.content.res.ColorStateList.valueOf(0xFF4B5563.toInt())
        }

        // Pasif Cihaz Bildirim Kartı
        val isPassive = prefs.getBoolean("is_device_passive", false)
        cardPassiveAlert.visibility = if (isPassive) android.view.View.VISIBLE else android.view.View.GONE
    }
}
