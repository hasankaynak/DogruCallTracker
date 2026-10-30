package com.mehmetdogru.calltracker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

class BootReceiver : BroadcastReceiver() {
    
    companion object {
        const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        Log.d(TAG, "Broadcast alındı: $action")

        if (action == Intent.ACTION_BOOT_COMPLETED ||
            action == "android.intent.action.QUICKBOOT_POWERON" ||
            action == "com.htc.intent.action.QUICKBOOT_POWERON" ||
            action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            
            Log.d(TAG, "═══ TELEFON AÇILDI VEYA UYGULAMA GÜNCELLENDİ — SERVİS BAŞLATILIYOR ═══")
            
            val prefs = context.getSharedPreferences("calltracker", Context.MODE_PRIVATE)
            val serviceEnabled = prefs.getBoolean("service_enabled", true)
            
            if (serviceEnabled) {
                Log.d(TAG, "Servis aktifti, başlatılıyor...")
                val serviceIntent = Intent(context, CallDetectorService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }
            } else {
                Log.d(TAG, "Servis kullanıcı tarafından durdurulmuştu, başlatılmıyor")
            }
        }
    }
}
