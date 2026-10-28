package com.mehmetdogru.calltracker

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * CT-001: Cihaz anahtari icin guvenli depolama.
 *
 * Cihaz anahtari, cagri kayitlarina yazma yetkisi verir; duz SharedPreferences'ta
 * saklanmasi dogru degildir (yedeklere ve root'lu cihazlarda diger uygulamalara acilir).
 * Android Keystore destekli EncryptedSharedPreferences kullanilir.
 *
 * Keystore erisimi basarisiz olursa (nadir OEM hatalari) uygulama COKMEZ:
 * duz prefs'e duser ve bunu loglar — cagri takibini tamamen durdurmak,
 * anahtari sifrelenmemis saklamaktan daha kotu bir sonuctur.
 */
object SecureStore {

    private const val TAG = "SecureStore"
    private const val SECURE_FILE = "calltracker_secure"
    private const val FALLBACK_FILE = "calltracker"
    private const val KEY_DEVICE_TOKEN = "device_token"

    @Volatile
    private var cached: SharedPreferences? = null

    private fun prefs(context: Context): SharedPreferences {
        cached?.let { return it }
        synchronized(this) {
            cached?.let { return it }
            val p = try {
                val masterKey = MasterKey.Builder(context.applicationContext)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build()
                EncryptedSharedPreferences.create(
                    context.applicationContext,
                    SECURE_FILE,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                )
            } catch (e: Exception) {
                Log.e(TAG, "Sifreli depo acilamadi, duz prefs'e dusuluyor: ${e.message}")
                context.applicationContext.getSharedPreferences(FALLBACK_FILE, Context.MODE_PRIVATE)
            }
            cached = p
            return p
        }
    }

    fun getDeviceToken(context: Context): String? =
        prefs(context).getString(KEY_DEVICE_TOKEN, null)?.takeIf { it.isNotBlank() }

    fun setDeviceToken(context: Context, token: String) {
        prefs(context).edit().putString(KEY_DEVICE_TOKEN, token.trim()).apply()
    }

    fun clearDeviceToken(context: Context) {
        prefs(context).edit().remove(KEY_DEVICE_TOKEN).apply()
    }

    fun hasDeviceToken(context: Context): Boolean = getDeviceToken(context) != null

    /** Loglarda/ekranda gostermek icin maskeli hali: ilk 8 karakter + gerisi gizli. */
    fun maskedDeviceToken(context: Context): String {
        val t = getDeviceToken(context) ?: return "— tanimli degil —"
        return if (t.length <= 8) "********" else t.take(8) + "…" + "*".repeat(8)
    }
}
