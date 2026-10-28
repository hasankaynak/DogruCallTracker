package com.mehmetdogru.calltracker

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log
import java.util.UUID

/**
 * CT-002: Kalici cagri kuyrugu (offline-first).
 * CT-009: Her olaya benzersiz eventId — sunucu tarafinda tekrar kaydi engellenir.
 *
 * Neden Room degil de dogrudan SQLite:
 * Room, KSP/kapt eklenti zinciri getirir. Bu uygulamada tek bir basit tablo var;
 * SQLiteOpenHelper Android'in icinde gelir, islemsel (transactional) ve es zamanli
 * erisime dayaniklidir. Yeni bagimlilik = yeni kirilma noktasi.
 *
 * Dayaniklilik sozlesmesi:
 *   Cagri once KUYRUGA YAZILIR, sonra gonderilmeye calisilir.
 *   Boylece surec oldurulse veya ag kopsa bile kayit kaybolmaz.
 */
class CallQueue private constructor(context: Context) :
    SQLiteOpenHelper(context.applicationContext, DB_NAME, null, DB_VERSION) {

    companion object {
        private const val TAG = "CallQueue"
        private const val DB_NAME = "calltracker_queue.db"
        private const val DB_VERSION = 1
        private const val TABLE = "pending_calls"

        /** Ust sinir: kuyrukta bu kadar denemeden sonra kayit "terk edildi" isaretlenir. */
        private const val MAX_ATTEMPTS = 20

        /** Bu yastan eski kayitlar da terk edilir (7 gun). */
        private const val MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000

        /**
         * Artan bekleme suresi — sonsuz/agresif retry pil tuketir.
         * 30sn, 1dk, 5dk, 15dk, 1sa, 3sa, sonrasi 6sa sabit.
         */
        private val BACKOFF_MS = longArrayOf(
            30_000L, 60_000L, 300_000L, 900_000L, 3_600_000L, 10_800_000L, 21_600_000L
        )

        @Volatile
        private var instance: CallQueue? = null

        fun get(context: Context): CallQueue {
            instance?.let { return it }
            synchronized(this) {
                instance?.let { return it }
                val q = CallQueue(context)
                instance = q
                return q
            }
        }

        fun newEventId(): String = UUID.randomUUID().toString()
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE $TABLE (
                id              INTEGER PRIMARY KEY AUTOINCREMENT,
                event_id        TEXT NOT NULL UNIQUE,
                endpoint        TEXT NOT NULL,
                payload         TEXT NOT NULL,
                created_at      INTEGER NOT NULL,
                attempts        INTEGER NOT NULL DEFAULT 0,
                next_attempt_at INTEGER NOT NULL DEFAULT 0,
                last_error      TEXT,
                abandoned       INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_ready ON $TABLE (abandoned, next_attempt_at)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Surum 1 — yukseltme yolu yok. Veri kaybetmemek icin tabloyu DUSURMUYORUZ.
        Log.w(TAG, "onUpgrade cagrildi ($oldVersion -> $newVersion) ancak islem tanimli degil.")
    }

    data class QueuedCall(
        val id: Long,
        val eventId: String,
        val endpoint: String,
        val payload: String,
        val attempts: Int
    )

    /** Cagriyi kalici olarak kuyruga yazar. Gonderim denemesinden ONCE cagrilmalidir. */
    fun enqueue(endpoint: String, payload: String, eventId: String): Long {
        return try {
            val values = ContentValues().apply {
                put("event_id", eventId)
                put("endpoint", endpoint)
                put("payload", payload)
                put("created_at", System.currentTimeMillis())
                put("attempts", 0)
                put("next_attempt_at", 0L)
            }
            val id = writableDatabase.insertWithOnConflict(
                TABLE, null, values, SQLiteDatabase.CONFLICT_IGNORE
            )
            Log.d(TAG, "Kuyruga eklendi: $endpoint / $eventId (id=$id)")
            id
        } catch (e: Exception) {
            Log.e(TAG, "Kuyruga yazilamadi: ${e.message}")
            -1
        }
    }

    /** Gonderilmeye hazir (bekleme suresi dolmus, terk edilmemis) kayitlar. */
    fun dequeueReady(limit: Int = 20): List<QueuedCall> {
        val now = System.currentTimeMillis()
        val list = mutableListOf<QueuedCall>()
        try {
            readableDatabase.query(
                TABLE,
                arrayOf("id", "event_id", "endpoint", "payload", "attempts"),
                "abandoned = 0 AND next_attempt_at <= ?",
                arrayOf(now.toString()),
                null, null,
                "created_at ASC",       // Olay sirasi korunur: incoming, end sirasi bozulmaz
                limit.toString()
            ).use { c ->
                while (c.moveToNext()) {
                    list.add(
                        QueuedCall(
                            id = c.getLong(0),
                            eventId = c.getString(1),
                            endpoint = c.getString(2),
                            payload = c.getString(3),
                            attempts = c.getInt(4)
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Kuyruk okunamadi: ${e.message}")
        }
        return list
    }

    /** Basarili gonderim — kaydi kuyruktan sil. */
    fun markSent(id: Long) {
        try {
            writableDatabase.delete(TABLE, "id = ?", arrayOf(id.toString()))
        } catch (e: Exception) {
            Log.e(TAG, "markSent hatasi: ${e.message}")
        }
    }

    /**
     * Basarisiz gonderim — deneme sayisini artir, bir sonraki denemeyi planla.
     * Deneme/yas sinirini asan kayitlar SILINMEZ, "terk edildi" isaretlenir;
     * boylece kullaniciya "N cagri gonderilemedi" olarak gosterilebilir.
     */
    fun markFailed(id: Long, error: String) {
        try {
            val db = writableDatabase
            db.beginTransaction()
            try {
                var attempts = 0
                var createdAt = System.currentTimeMillis()
                db.query(TABLE, arrayOf("attempts", "created_at"), "id = ?",
                    arrayOf(id.toString()), null, null, null).use { c ->
                    if (c.moveToFirst()) {
                        attempts = c.getInt(0)
                        createdAt = c.getLong(1)
                    }
                }

                val newAttempts = attempts + 1
                val tooOld = System.currentTimeMillis() - createdAt > MAX_AGE_MS
                val abandon = newAttempts >= MAX_ATTEMPTS || tooOld

                val delay = BACKOFF_MS[minOf(newAttempts, BACKOFF_MS.size - 1)]
                val values = ContentValues().apply {
                    put("attempts", newAttempts)
                    put("next_attempt_at", System.currentTimeMillis() + delay)
                    put("last_error", error.take(200))
                    if (abandon) put("abandoned", 1)
                }
                db.update(TABLE, values, "id = ?", arrayOf(id.toString()))
                db.setTransactionSuccessful()

                if (abandon) {
                    Log.w(TAG, "Kayit terk edildi (id=$id, deneme=$newAttempts, eski=$tooOld)")
                }
            } finally {
                db.endTransaction()
            }
        } catch (e: Exception) {
            Log.e(TAG, "markFailed hatasi: ${e.message}")
        }
    }

    /** Gonderilmeyi bekleyen kayit sayisi. */
    fun pendingCount(): Int = count("abandoned = 0")

    /** Vazgecilmis (gonderilemeyen) kayit sayisi — kullaniciya gosterilmeli. */
    fun abandonedCount(): Int = count("abandoned = 1")

    private fun count(where: String): Int = try {
        readableDatabase.rawQuery("SELECT COUNT(*) FROM $TABLE WHERE $where", null).use { c ->
            if (c.moveToFirst()) c.getInt(0) else 0
        }
    } catch (e: Exception) {
        Log.e(TAG, "count hatasi: ${e.message}")
        0
    }

    /** Terk edilmis kayitlari yeniden dener (kullanici "Tekrar Dene" derse). */
    fun retryAbandoned() {
        try {
            val values = ContentValues().apply {
                put("abandoned", 0)
                put("attempts", 0)
                put("next_attempt_at", 0L)
            }
            writableDatabase.update(TABLE, values, "abandoned = 1", null)
        } catch (e: Exception) {
            Log.e(TAG, "retryAbandoned hatasi: ${e.message}")
        }
    }
}
