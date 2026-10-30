package com.mehmetdogru.calltracker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log
import java.io.File

class SilentInstallReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "SilentInstallReceiver"
        const val ACTION_INSTALL_STATUS = "com.mehmetdogru.calltracker.INSTALL_STATUS"
        const val EXTRA_SESSION_ID = "session_id"
        const val EXTRA_APK_PATH = "apk_path"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_INSTALL_STATUS) return

        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        val sessionId = intent.getIntExtra(EXTRA_SESSION_ID, -1)
        val apkPath = intent.getStringExtra(EXTRA_APK_PATH)

        Log.d(TAG, "Install result received. Status: $status, Message: $message, Session: $sessionId")

        when (status) {
            PackageInstaller.STATUS_SUCCESS -> {
                Log.d(TAG, "🎉 Güncelleme arka planda dokunmadan başarıyla kuruldu!")
                // İndirilen geçici APK'yı temizle
                if (!apkPath.isNullOrBlank()) {
                    try { File(apkPath).delete() } catch (_: Exception) {}
                }

                // Servisi tekrar başlat
                try {
                    val serviceIntent = Intent(context, CallDetectorService::class.java)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startForegroundService(serviceIntent)
                    } else {
                        context.startService(serviceIntent)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Servis başlatma hatası: ${e.message}")
                }
            }

            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                Log.w(TAG, "⚠️ Sistem kullanıcı onayı istedi (Eski Android veya izin kısıtı). Onay ekranı açılıyor...")
                val confirmIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_INTENT)
                }
                confirmIntent?.apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(this)
                }
            }

            else -> {
                Log.e(TAG, "❌ Arka plan kurulumu başarısız: $status ($message)")
                // Hata durumunda da geçici APK'yı temizle
                if (!apkPath.isNullOrBlank()) {
                    try { File(apkPath).delete() } catch (_: Exception) {}
                }
            }
        }
    }
}
