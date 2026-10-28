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
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == "android.intent.action.QUICKBOOT_POWERON" ||
            intent.action == "com.htc.intent.action.QUICKBOOT_POWERON") {
            
            Log.d(TAG, "═══ TELEFON AÇILDI — SERVİS KONTROL EDİLİYOR ═══")
            
            // Kullanıcı servisi manuel durdurduysa başlatma
            val prefs = context.getSharedPreferences("calltracker", Context.MODE_PRIVATE)
            val serviceEnabled = prefs.getBoolean("service_enabled", false)
            
            if (serviceEnabled) {
                Log.d(TAG, "Servis aktifti, yeniden başlatılıyor...")
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
