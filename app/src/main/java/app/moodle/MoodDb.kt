package app.moodle

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/** Single-table local database. Nothing here ever leaves the device. */
class MoodDb private constructor(context: Context) :
    SQLiteOpenHelper(context.applicationContext, "moodle.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE moments (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                ts INTEGER NOT NULL,
                zone TEXT NOT NULL,
                mood INTEGER NOT NULL,
                photo TEXT NOT NULL,
                lat REAL NOT NULL,
                lon REAL NOT NULL,
                accuracy REAL NOT NULL,
                approx INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_moments_ts ON moments(ts)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Schema v1 only so far.
    }

    fun insert(m: NewMoment): Long {
        val values = ContentValues().apply {
            put("ts", m.epochMillis)
            put("zone", m.zoneId)
            put("mood", m.mood.id)
            put("photo", m.photoName)
            put("lat", m.latitude)
            put("lon", m.longitude)
            put("accuracy", m.accuracyMeters)
            put("approx", if (m.approximate) 1 else 0)
        }
        val id = writableDatabase.insert("moments", null, values)
        if (id < 0) throw IllegalStateException("Could not insert moment")
        return id
    }

    /** Moments with [fromMillis] <= ts < [toMillis], oldest first. */
    fun between(fromMillis: Long, toMillis: Long): List<Moment> {
        val out = ArrayList<Moment>()
        readableDatabase.query(
            "moments", null, "ts >= ? AND ts < ?",
            arrayOf(fromMillis.toString(), toMillis.toString()),
            null, null, "ts ASC",
        ).use { c ->
            val iId = c.getColumnIndexOrThrow("id")
            val iTs = c.getColumnIndexOrThrow("ts")
            val iZone = c.getColumnIndexOrThrow("zone")
            val iMood = c.getColumnIndexOrThrow("mood")
            val iPhoto = c.getColumnIndexOrThrow("photo")
            val iLat = c.getColumnIndexOrThrow("lat")
            val iLon = c.getColumnIndexOrThrow("lon")
            val iAcc = c.getColumnIndexOrThrow("accuracy")
            val iApprox = c.getColumnIndexOrThrow("approx")
            while (c.moveToNext()) {
                val mood = Mood.fromId(c.getInt(iMood)) ?: continue
                out += Moment(
                    id = c.getLong(iId),
                    epochMillis = c.getLong(iTs),
                    zoneId = c.getString(iZone),
                    mood = mood,
                    photoName = c.getString(iPhoto),
                    latitude = c.getDouble(iLat),
                    longitude = c.getDouble(iLon),
                    accuracyMeters = c.getFloat(iAcc),
                    approximate = c.getInt(iApprox) != 0,
                )
            }
        }
        return out
    }

    fun count(): Int =
        readableDatabase.rawQuery("SELECT COUNT(*) FROM moments", null).use { c ->
            if (c.moveToFirst()) c.getInt(0) else 0
        }

    /** Deletes the row and returns the selfie file name that belonged to it (or null). */
    fun delete(id: Long): String? {
        val db = writableDatabase
        val photo = db.query("moments", arrayOf("photo"), "id = ?", arrayOf(id.toString()), null, null, null)
            .use { c -> if (c.moveToFirst()) c.getString(0) else null }
        db.delete("moments", "id = ?", arrayOf(id.toString()))
        return photo
    }

    companion object {
        @Volatile
        private var instance: MoodDb? = null

        /** Test hook: drops the cached helper so each test starts on a fresh database. */
        internal fun reset() = synchronized(this) {
            instance?.close()
            instance = null
        }

        fun get(context: Context): MoodDb =
            instance ?: synchronized(this) {
                instance ?: MoodDb(context).also { instance = it }
            }
    }
}
