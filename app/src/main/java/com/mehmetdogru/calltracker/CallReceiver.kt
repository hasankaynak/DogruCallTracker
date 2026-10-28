package com.mehmetdogru.calltracker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.TelephonyManager
import android.util.Log

/**
 * CallReceiver — Gelen ve giden çağrı numaralarını yakalar
 */
class CallReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "CallReceiver"
        
        @Volatile
        var lastCapturedIncomingNumber: String? = null

        /** CT-018: Uretim loglarinda telefon numarasini maskele (KVKK). */
        fun mask(number: String?): String {
            if (number.isNullOrEmpty()) return "YOK"
            if (BuildConfig.DEBUG) return number
            return if (number.length <= 2) "**" else "*".repeat(number.length - 2) + number.takeLast(2)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action == TelephonyManager.ACTION_PHONE_STATE_CHANGED) {
            val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE)
            val incomingNumber = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)
            
            Log.d(TAG, "📞 Broadcast: State=$state, Number=${mask(incomingNumber)}")

            if (state == TelephonyManager.EXTRA_STATE_RINGING) {
                if (!incomingNumber.isNullOrEmpty()) {
                    lastCapturedIncomingNumber = incomingNumber
                    Log.d(TAG, "🎯🎯 NUMARA YAKALANDI: ${mask(incomingNumber)}")
                }
            } else if (state == TelephonyManager.EXTRA_STATE_IDLE) {
                // CallDetectorService çağrıyı işleyene kadar numarayı hemen sıfırlama,
                // böylece CallDetectorService state transition sırasında numarayı kaybetmez.
            }
        } else if (action == Intent.ACTION_NEW_OUTGOING_CALL) {
            val outgoingNumber = intent.getStringExtra(Intent.EXTRA_PHONE_NUMBER)
            Log.d(TAG, "📤 Giden Arama: ${mask(outgoingNumber)}")
        }
    }
}
