package dev.sleepalarm.sensors

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import dev.sleepalarm.model.*

/** Credential-protected storage; samples never enter the direct-boot alarm preferences. */
class SensorDatabase(context: Context) : SQLiteOpenHelper(context, "observations-v1.db", null, 3) {
    override fun onCreate(db: SQLiteDatabase) {
        auditTable(db)
        db.execSQL("CREATE TABLE motion(session TEXT, at INTEGER, energy REAL, covered INTEGER, received INTEGER, PRIMARY KEY(session, at))")
        db.execSQL("CREATE TABLE heart(session TEXT, at INTEGER, bpm REAL, quality REAL, received INTEGER, PRIMARY KEY(session, at))")
    }
    private fun auditTable(db: SQLiteDatabase) { db.execSQL("CREATE TABLE IF NOT EXISTS audit(session TEXT, nanos INTEGER, measured INTEGER, received INTEGER, energy REAL, accuracy INTEGER, reason TEXT, PRIMARY KEY(session,nanos))") }
    override fun onConfigure(db: SQLiteDatabase) { super.onConfigure(db); db.enableWriteAheadLogging() }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 3) auditTable(db)
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE motion ADD COLUMN received INTEGER NOT NULL DEFAULT 9223372036854775807")
            db.execSQL("ALTER TABLE heart ADD COLUMN quality REAL NOT NULL DEFAULT 1.0")
            db.execSQL("ALTER TABLE heart ADD COLUMN received INTEGER NOT NULL DEFAULT 9223372036854775807")
            // Old records have no arrival times: do not pretend they are causally replayable.
        }
    }
    fun addMotion(id: String, p: MotionSample): Boolean {
        return writableDatabase.insertWithOnConflict("motion", null, ContentValues().apply {
            put("session", id); put("at", p.at); put("energy", p.energy); put("covered", p.coveredMillis); put("received", p.receivedAt)
        }, SQLiteDatabase.CONFLICT_IGNORE) >= 0
    }
    data class Audit(val nanos: Long, val measured: Long, val received: Long, val energy: Double?, val accuracy: Int, val reason: String)
    fun audit(id: String, samples: List<Audit>) {
        val db = writableDatabase; db.beginTransaction()
        try {
            samples.forEach { p -> db.insertWithOnConflict("audit", null, ContentValues().apply {
                put("session", id); put("nanos", p.nanos); put("measured", p.measured); put("received", p.received)
                if (p.energy?.isFinite() == true) put("energy", p.energy) else putNull("energy")
                put("accuracy", p.accuracy); put("reason", p.reason)
            }, SQLiteDatabase.CONFLICT_IGNORE) }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
    fun addHeart(id: String, values: List<HeartSample>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            values.forEach { p ->
                db.insertWithOnConflict("heart", null, ContentValues().apply {
                    put("session", id); put("at", p.at); put("bpm", p.bpm); put("quality", p.quality); put("received", p.receivedAt)
                }, SQLiteDatabase.CONFLICT_IGNORE)
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
    fun motion(id: String, since: Long): List<MotionSample> = readableDatabase.rawQuery(
        "SELECT at, energy, covered, received FROM motion WHERE session=? AND at>=? ORDER BY at", arrayOf(id, since.toString()),
    ).use { c -> buildList { while (c.moveToNext()) add(MotionSample(c.getLong(0), c.getDouble(1), c.getLong(2), c.getLong(3))) } }
    fun heart(id: String, since: Long): List<HeartSample> = readableDatabase.rawQuery(
        "SELECT at, bpm, quality, received FROM heart WHERE session=? AND at>=? ORDER BY at", arrayOf(id, since.toString()),
    ).use { c -> buildList { while (c.moveToNext()) add(HeartSample(c.getLong(0), c.getDouble(1), c.getDouble(2), c.getLong(3))) } }
    fun prune(now: Long) {
        listOf("motion", "heart").forEach { writableDatabase.delete(it, "at<?", arrayOf((now - 48 * 3_600_000L).toString())) }
        writableDatabase.delete("audit", "measured<?", arrayOf((now - 48 * 3_600_000L).toString()))
    }
    fun erase() { writableDatabase.delete("audit", null, null); writableDatabase.delete("motion", null, null); writableDatabase.delete("heart", null, null) }
}
