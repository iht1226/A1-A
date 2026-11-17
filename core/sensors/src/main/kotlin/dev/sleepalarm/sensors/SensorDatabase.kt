package dev.sleepalarm.sensors

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import dev.sleepalarm.model.*

/** Credential-protected storage; samples never enter the direct-boot alarm preferences. */
class SensorDatabase(context: Context) : SQLiteOpenHelper(context, "observations-v1.db", null, 2) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE motion(session TEXT, at INTEGER, energy REAL, covered INTEGER, received INTEGER, PRIMARY KEY(session, at))")
        db.execSQL("CREATE TABLE heart(session TEXT, at INTEGER, bpm REAL, quality REAL, received INTEGER, PRIMARY KEY(session, at))")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE motion ADD COLUMN received INTEGER NOT NULL DEFAULT 9223372036854775807")
            db.execSQL("ALTER TABLE heart ADD COLUMN quality REAL NOT NULL DEFAULT 1.0")
            db.execSQL("ALTER TABLE heart ADD COLUMN received INTEGER NOT NULL DEFAULT 9223372036854775807")
            // Old records have no arrival times: do not pretend they are causally replayable.
        }
    }
    fun addMotion(id: String, p: MotionSample) {
        writableDatabase.insertWithOnConflict("motion", null, ContentValues().apply {
            put("session", id); put("at", p.at); put("energy", p.energy); put("covered", p.coveredMillis); put("received", p.receivedAt)
        }, SQLiteDatabase.CONFLICT_REPLACE)
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
    }
    fun erase() { writableDatabase.delete("motion", null, null); writableDatabase.delete("heart", null, null) }
}
