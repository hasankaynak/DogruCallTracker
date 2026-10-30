package com.mehmetdogru.calltracker

import android.content.Context
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

object BackgroundUpdateManager {

    private const val TAG = "BackgroundUpdate"
    private const val PREFS_NAME = "calltracker_updates"
    private const val KEY_LAST_ATTEMPT_TIME = "last_attempt_time"
    private const val KEY_LAST_ATTEMPT_VER = "last_attempt_ver"
    private const val RETRY_COOLDOWN_MS = 20 * 60 * 1000L // 20 dakika bekleme

    private val isDownloading = AtomicBoolean(false)

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .build()
    }

    fun checkAndStartSilentUpdate(
        context: Context,
        updateUrl: String,
        newVersionName: String,
        newVersionCode: Int = 0
    ) {
        if (updateUrl.isBlank()) return

        val currentVersionName = BuildConfig.VERSION_NAME
        val currentVersionCode = BuildConfig.VERSION_CODE

        val isCodeNewer = newVersionCode > 0 && newVersionCode > currentVersionCode
        val isNameNewer = newVersionName.isNotBlank() && UpdateManager.isNewer(newVersionName, currentVersionName)

        if (!isCodeNewer && !isNameNewer) {
            // Zaten güncel veya eski sürüm
            return
        }

        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val lastVer = prefs.getString(KEY_LAST_ATTEMPT_VER, "") ?: ""
        val lastTime = prefs.getLong(KEY_LAST_ATTEMPT_TIME, 0L)
        val now = System.currentTimeMillis()

        // Eğer aynı sürüm son 20 dakika içinde denenip başarısız olduysa sunucuyu ve pili yorma
        if (lastVer == newVersionName && (now - lastTime) < RETRY_COOLDOWN_MS) {
            return
        }

        if (!isDownloading.compareAndSet(false, true)) {
            Log.d(TAG, "Zaten bir güncelleme indirmesi devam ediyor.")
            return
        }

        Log.d(TAG, "🚀 Yeni sürüm algılandı: v$newVersionName (Mevcut: v$currentVersionName). Arka planda indirme başlatılıyor...")

        Thread {
            try {
                prefs.edit()
                    .putString(KEY_LAST_ATTEMPT_VER, newVersionName)
                    .putLong(KEY_LAST_ATTEMPT_TIME, now)
                    .apply()

                val request = Request.Builder()
                    .url(updateUrl)
                    .header("User-Agent", "DogruCallTracker-Background-Updater")
                    .build()

                val response = client.newCall(request).execute()
                if (!response.isSuccessful) {
                    throw IOException("Sunucu yanıt kodu: ${response.code}")
                }

                val body = response.body ?: throw IOException("Yanıt gövdesi boş")

                val targetDir = context.externalCacheDir ?: context.cacheDir
                val targetFile = File(targetDir, "calltracker_silent_update.apk")
                if (targetFile.exists()) targetFile.delete()

                FileOutputStream(targetFile).use { fos ->
                    body.byteStream().copyTo(fos)
                }

                Log.d(TAG, "📦 APK başarıyla indirildi: ${targetFile.length()} bayt. Paket doğrulanıyor...")

                // İndirilen dosyanın sağlam bir APK olduğunu ve paket adının eşleştiğini doğrula
                val pm = context.packageManager
                val archiveInfo = pm.getPackageArchiveInfo(targetFile.absolutePath, 0)
                if (archiveInfo == null || archiveInfo.packageName != context.packageName) {
                    targetFile.delete()
                    throw IOException("İndirilen dosya bozuk veya paket adı uyuşmuyor: ${archiveInfo?.packageName}")
                }

                Log.d(TAG, "✅ APK doğrulandı (${archiveInfo.packageName} v${archiveInfo.versionName}). Sessiz kurulum başlatılıyor...")

                val installed = SilentInstaller.installSilently(context, targetFile)
                if (installed) {
                    Log.d(TAG, "🎉 Sessiz kurulum komutu işletim sistemine iletildi!")
                } else {
                    Log.w(TAG, "PackageInstaller session başlatılamadı, fallback başlatılıyor...")
                    UpdateManager.installApk(context, targetFile)
                }

            } catch (e: Exception) {
                Log.e(TAG, "❌ Arka plan güncelleme hatası: ${e.message}", e)
            } finally {
                isDownloading.set(false)
            }
        }.start()
    }
}
