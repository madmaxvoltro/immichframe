package dev.immichframe

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject

/** Local taste memory: likes, "show less", skips, hidden, when shown. */
class Stats(ctx: Context) : SQLiteOpenHelper(ctx, "stats.db", null, 1) {

    class Row(
        val likes: Int, val less: Int, val skips: Int, val hidden: Boolean,
        val shown: Int, val lastShown: Long, val lastSkip: Long,
    )

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE s(id TEXT PRIMARY KEY, likes INTEGER DEFAULT 0, less INTEGER DEFAULT 0, " +
                "skips INTEGER DEFAULT 0, hidden INTEGER DEFAULT 0, shown INTEGER DEFAULT 0, " +
                "last_shown INTEGER DEFAULT 0, last_skip INTEGER DEFAULT 0, people TEXT DEFAULT '')"
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, o: Int, n: Int) {}

    private fun ensure(id: String) {
        writableDatabase.insertWithOnConflict("s", null, ContentValues().apply { put("id", id) },
            SQLiteDatabase.CONFLICT_IGNORE)
    }

    fun all(): Map<String, Row> {
        val out = HashMap<String, Row>()
        readableDatabase.rawQuery(
            "SELECT id, likes, less, skips, hidden, shown, last_shown, last_skip FROM s", null
        ).use { c ->
            while (c.moveToNext()) out[c.getString(0)] = Row(
                c.getInt(1), c.getInt(2), c.getInt(3), c.getInt(4) != 0,
                c.getInt(5), c.getLong(6), c.getLong(7)
            )
        }
        return out
    }

    fun shown(id: String) {
        ensure(id)
        writableDatabase.execSQL("UPDATE s SET shown = shown + 1, last_shown = ? WHERE id = ?",
            arrayOf<Any>(System.currentTimeMillis(), id))
    }

    fun setLike(id: String, liked: Boolean, people: List<String>) {
        ensure(id)
        writableDatabase.execSQL("UPDATE s SET likes = ?, people = ? WHERE id = ?",
            arrayOf<Any>(if (liked) 1 else 0, people.joinToString("|"), id))
    }

    fun less(id: String) {
        ensure(id)
        writableDatabase.execSQL("UPDATE s SET less = less + 1 WHERE id = ?", arrayOf<Any>(id))
    }

    fun skip(id: String) {
        ensure(id)
        writableDatabase.execSQL("UPDATE s SET skips = skips + 1, last_skip = ? WHERE id = ?",
            arrayOf<Any>(System.currentTimeMillis(), id))
    }

    fun hide(id: String, hidden: Boolean = true) {
        ensure(id)
        writableDatabase.execSQL("UPDATE s SET hidden = ? WHERE id = ?", arrayOf<Any>(if (hidden) 1 else 0, id))
    }

    /** person name -> how many liked photos they appear in */
    fun likedPeople(): Map<String, Int> {
        val out = HashMap<String, Int>()
        readableDatabase.rawQuery("SELECT people FROM s WHERE likes > 0 AND people != ''", null).use { c ->
            while (c.moveToNext()) c.getString(0).split('|').filter { it.isNotBlank() }
                .forEach { out[it] = (out[it] ?: 0) + 1 }
        }
        return out
    }

    fun summary(): JSONObject {
        fun top(col: String): JSONArray {
            val arr = JSONArray()
            readableDatabase.rawQuery("SELECT id, $col FROM s WHERE $col > 0 ORDER BY $col DESC LIMIT 20", null).use { c ->
                while (c.moveToNext()) arr.put(JSONObject().put("id", c.getString(0)).put(col, c.getInt(1)))
            }
            return arr
        }
        val counts = JSONObject()
        readableDatabase.rawQuery(
            "SELECT COUNT(*), SUM(likes > 0), SUM(hidden), SUM(less > 0), SUM(shown) FROM s", null
        ).use { c ->
            if (c.moveToFirst()) counts.put("tracked", c.getInt(0)).put("liked", c.getInt(1))
                .put("hidden", c.getInt(2)).put("less", c.getInt(3)).put("shows", c.getInt(4))
        }
        val people = JSONObject()
        likedPeople().entries.sortedByDescending { it.value }.forEach { people.put(it.key, it.value) }
        return JSONObject().put("counts", counts).put("mostShown", top("shown"))
            .put("mostSkipped", top("skips")).put("likedPeople", people)
    }
}
