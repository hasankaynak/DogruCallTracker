package com.mehmetdogru.calltracker

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log
import java.io.File
import java.io.FileInputStream

object SilentInstaller {

    private const val TAG = "SilentInstaller"

    /**
     * APK dosyasını arka planda kullanıcı etkileşimi olmadan (sessizce) kurmaya çalışır.
     * 1) Root varsa: 'pm install -r' ile 100% sessiz
     * 2) Android 12+ (API 31+): PackageInstaller USER_ACTION_NOT_REQUIRED ile 100% sessiz
     * 3) Android 11 ve altı: PackageInstaller session veya fallback intent
     */
    fun installSilently(context: Context, apkFile: File): Boolean {
        if (!apkFile.exists() || apkFile.length() <= 0L) {
            Log.e(TAG, "APK dosyası mevcut değil veya boş: ${apkFile.absolutePath}")
            return false
        }

        // 1. Root Kontrolü (Rootlu cihazlarda doğrudan sessiz kurulum)
        if (tryRootInstall(apkFile)) {
            Log.d(TAG, "✅ Root üzerinden sessiz kurulum başarıyla tetiklendi")
            return true
        }

        // 2. Android Standart PackageInstaller Session API
        return try {
            val packageInstaller = context.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                setAppPackageName(context.packageName)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    // Android 12+ (API 31+): Kullanıcının dokunmasına gerek kalmadan arka planda kur!
                    setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
                }
            }

            val sessionId = packageInstaller.createSession(params)
            val session = packageInstaller.openSession(sessionId)

            session.openWrite("calltracker_update", 0, apkFile.length()).use { out ->
                FileInputStream(apkFile).use { inStream ->
                    inStream.copyTo(out)
                }
                session.fsync(out)
            }

            val receiverIntent = Intent(context, SilentInstallReceiver::class.java).apply {
                action = SilentInstallReceiver.ACTION_INSTALL_STATUS
                putExtra(SilentInstallReceiver.EXTRA_SESSION_ID, sessionId)
                putExtra(SilentInstallReceiver.EXTRA_APK_PATH, apkFile.absolutePath)
            }

            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }

            val pendingIntent = PendingIntent.getBroadcast(
                context,
                sessionId,
                receiverIntent,
                flags
            )

            session.commit(pendingIntent.intentSender)
            session.close()
            Log.d(TAG, "🚀 PackageInstaller session #$sessionId arka planda commit edildi.")
            true
        } catch (e: Exception) {
            Log.e(TAG, "PackageInstaller kurulum hatası: ${e.message}", e)
            false
        }
    }

    private fun tryRootInstall(apkFile: File): Boolean {
        return try {
            val process = Runtime.getRuntime().exec("su")
            val os = process.outputStream
            val cmd = "pm install -r \"${apkFile.absolutePath}\"\nexit\n"
            os.write(cmd.toByteArray())
            os.flush()
            os.close()
            val exitCode = process.waitFor()
            exitCode == 0
        } catch (_: Exception) {
            false
        }
    }
}
