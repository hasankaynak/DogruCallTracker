package com.mehmetdogru.calltracker

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast

class CallTrackerAdminReceiver : DeviceAdminReceiver() {

    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
        Toast.makeText(context, "🛡️ Doğru CRM Kaldırma Koruması Aktifleştirildi", Toast.LENGTH_LONG).show()
    }

    override fun onDisabled(context: Context, intent: Intent) {
        super.onDisabled(context, intent)
        Toast.makeText(context, "⚠️ Cihaz yöneticisi devre dışı bırakıldı", Toast.LENGTH_LONG).show()
    }

    override fun onDisableRequested(context: Context, intent: Intent): CharSequence {
        return "⚠️ DİKKAT: Bu uygulama şirket santral ve arama takibi için zorunludur. Cihaz yöneticisini kapatırsanız çağrı kayıtları durdurulur ve yönetim sistemine ihlal bildirimi iletilir."
    }
}
