package com.mehmetdogru.calltracker

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.core.content.FileProvider
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

object UpdateManager {

    private const val TAG = "UpdateManager"
    const val GITHUB_OWNER = "hasankaynak"
    const val GITHUB_REPO = "DogruCallTracker"
    private const val RELEASE_API_URL = "https://api.github.com/repos/$GITHUB_OWNER/$GITHUB_REPO/releases/latest"

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    data class ReleaseInfo(
        val hasUpdate: Boolean,
        val latestVersion: String,
        val currentVersion: String,
        val title: String,
        val notes: String,
        val apkUrl: String?,
        val htmlUrl: String
    )

    /**
     * Semantik sürüm karşılaştırması: remote > current ise true
     */
    fun isNewer(remote: String, current: String): Boolean {
        val rClean = remote.trim().removePrefix("v").removePrefix("V")
        val cClean = current.trim().removePrefix("v").removePrefix("V")
        if (rClean == cClean) return false

        val rParts = rClean.split(".").mapNotNull { it.toIntOrNull() }
        val cParts = cClean.split(".").mapNotNull { it.toIntOrNull() }

        val maxLen = maxOf(rParts.size, cParts.size)
        for (i in 0 until maxLen) {
            val r = rParts.getOrElse(i) { 0 }
            val c = cParts.getOrElse(i) { 0 }
            if (r > c) return true
            if (r < c) return false
        }
        return false
    }

    /**
     * GitHub Releases API üzerinden güncellemeleri denetler
     */
    fun checkForUpdates(activity: Activity, isManual: Boolean = false, onResult: ((Boolean) -> Unit)? = null) {
        Thread {
            try {
                Log.d(TAG, "GitHub Releases kontrol ediliyor: $RELEASE_API_URL")
                val request = Request.Builder()
                    .url(RELEASE_API_URL)
                    .header("Accept", "application/vnd.github.v3+json")
                    .header("User-Agent", "DogruCallTracker-App")
                    .build()

                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        Log.w(TAG, "GitHub API HTTP ${response.code}")
                        if (isManual) {
                            activity.runOnUiThread {
                                Toast.makeText(activity, "Güncelleme sunucusuna ulaşılamadı (HTTP ${response.code})", Toast.LENGTH_SHORT).show()
                                onResult?.invoke(false)
                            }
                        }
                        return@use
                    }

                    val jsonStr = response.body?.string() ?: return@use
                    val json = JSONObject(jsonStr)

                    val tagName = json.optString("tag_name", "").removePrefix("v")
                    val releaseTitle = json.optString("name", "Sürüm $tagName")
                    val releaseNotes = json.optString("body", "Hata düzeltmeleri ve performans iyileştirmeleri yapıldı.")
                    val htmlUrl = json.optString("html_url", "https://github.com/$GITHUB_OWNER/$GITHUB_REPO/releases")

                    var apkUrl: String? = null
                    val assets = json.optJSONArray("assets")
                    if (assets != null) {
                        for (i in 0 until assets.length()) {
                            val asset = assets.getJSONObject(i)
                            val name = asset.optString("name", "")
                            if (name.endsWith(".apk", ignoreCase = true)) {
                                apkUrl = asset.optString("browser_download_url", null)
                                break
                            }
                        }
                    }

                    val currentVersion = BuildConfig.VERSION_NAME
                    val hasUpdate = isNewer(tagName, currentVersion)

                    activity.runOnUiThread {
                        onResult?.invoke(hasUpdate)
                        if (hasUpdate && !apkUrl.isNullOrEmpty()) {
                            showUpdateDialog(activity, ReleaseInfo(
                                hasUpdate = true,
                                latestVersion = tagName,
                                currentVersion = currentVersion,
                                title = releaseTitle,
                                notes = releaseNotes,
                                apkUrl = apkUrl,
                                htmlUrl = htmlUrl
                            ))
                        } else if (isManual) {
                            Toast.makeText(activity, "✅ Uygulamanız güncel (v$currentVersion)", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Güncelleme kontrol hatası: ${e.message}")
                if (isManual) {
                    activity.runOnUiThread {
                        Toast.makeText(activity, "Güncelleme kontrolü başarısız: ${e.message}", Toast.LENGTH_SHORT).show()
                        onResult?.invoke(false)
                    }
                }
            }
        }.start()
    }

    private fun showUpdateDialog(activity: Activity, info: ReleaseInfo) {
        if (activity.isFinishing || activity.isDestroyed) return

        val msg = """
            Yeni sürüm GitHub üzerinde yayınlandı!
            
            📌 Sürüm: v${info.latestVersion} (Mevcut: v${info.currentVersion})
            
            📝 Yenilikler:
            ${info.notes}
        """.trimIndent()

        AlertDialog.Builder(activity)
            .setTitle("🚀 Güncelleme Mevcut: ${info.title}")
            .setMessage(msg)
            .setPositiveButton("Şimdi Güncelle") { _, _ ->
                info.apkUrl?.let { url ->
                    downloadAndInstall(activity, url, info.latestVersion)
                }
            }
            .setNegativeButton("Daha Sonra", null)
            .setNeutralButton("GitHub'da Gör") { _, _ ->
                try {
                    val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(info.htmlUrl))
                    activity.startActivity(browserIntent)
                } catch (_: Exception) {}
            }
            .show()
    }

    /**
     * GitHub'dan APK'yı indirir ve kurulumu başlatır
     */
    private fun downloadAndInstall(activity: Activity, apkUrl: String, newVersion: String) {
        val progressDialog = AlertDialog.Builder(activity)
            .setTitle("Güncelleme İndiriliyor")
            .setMessage("Lütfen bekleyin, GitHub'dan yeni sürüm (v$newVersion) indiriliyor...")
            .setCancelable(false)
            .create()

        progressDialog.show()

        Thread {
            try {
                val request = Request.Builder()
                    .url(apkUrl)
                    .header("User-Agent", "DogruCallTracker-App")
                    .build()

                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                    val body = response.body ?: throw IOException("Yanıt gövdesi boş")

                    val cacheDir = activity.externalCacheDir ?: activity.cacheDir
                    val apkFile = File(cacheDir, "DogruCallTracker-v$newVersion.apk")
                    if (apkFile.exists()) apkFile.delete()

                    apkFile.outputStream().use { fos ->
                        body.byteStream().copyTo(fos)
                    }

                    activity.runOnUiThread {
                        try { progressDialog.dismiss() } catch (_: Exception) {}
                        installApk(activity, apkFile)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "İndirme hatası: ${e.message}")
                activity.runOnUiThread {
                    try { progressDialog.dismiss() } catch (_: Exception) {}
                    Toast.makeText(activity, "APK indirme hatası: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    /**
     * İndirilen APK dosyasını sisteme kurmak üzere Intent başlatır
     */
    fun installApk(context: Context, apkFile: File) {
        if (!apkFile.exists()) {
            Toast.makeText(context, "APK dosyası bulunamadı", Toast.LENGTH_SHORT).show()
            return
        }

        // Android 8.0+ için bilinmeyen kaynaklardan yükleme izni kontrolü
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!context.packageManager.canRequestPackageInstalls()) {
                val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                    data = Uri.parse("package:${context.packageName}")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                Toast.makeText(context, "Lütfen güncellemeyi yüklemek için izin verin ve işlemi tekrarlayın.", Toast.LENGTH_LONG).show()
                return
            }
        }

        try {
            val contentUri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                apkFile
            )

            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(contentUri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            context.startActivity(installIntent)
        } catch (e: Exception) {
            Log.e(TAG, "Kurulum başlatma hatası: ${e.message}")
            Toast.makeText(context, "Kurulum başlatılamadı: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
}
