package dev.immichframe

import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

/**
 * A time window with a screen mode.
 *   on   = always on, present or not
 *   off  = always off, even if someone is there (a touch still overrides for a while)
 *   auto = off until someone is noticed (sound or touch), then on for "stay on" minutes
 * Outside every rule the frame uses auto when presence sensing is on, else on.
 * Rules are checked top to bottom; the first match wins.
 */
data class Rule(val mode: String, val start: String, val end: String, val days: Set<String>) {

    fun toJson(): JSONObject = JSONObject().put("mode", mode).put("start", start).put("end", end)
        .put("days", JSONArray(DAYS.filter { it in days }))

    /** Overnight windows (22:00–07:00) belong to the day they start on. */
    fun matches(c: Calendar): Boolean {
        val s = minutes(start) ?: return false
        val e = minutes(end) ?: return false
        val now = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE)
        val today = DAYS[(c.get(Calendar.DAY_OF_WEEK) + 5) % 7]          // Calendar: Sun=1 → "sun"
        val yesterday = DAYS[(c.get(Calendar.DAY_OF_WEEK) + 4) % 7]
        return when {
            s == e -> today in days                                     // whole day
            s < e -> today in days && now >= s && now < e
            else -> (today in days && now >= s) || (yesterday in days && now < e)
        }
    }

    companion object {
        val DAYS = listOf("mon", "tue", "wed", "thu", "fri", "sat", "sun")
        val MODES = setOf("on", "off", "auto")

        fun minutes(hhmm: String): Int? {
            val p = hhmm.trim().split(":")
            val h = p.getOrNull(0)?.toIntOrNull() ?: return null
            val m = p.getOrNull(1)?.toIntOrNull() ?: 0
            if (h !in 0..24 || m !in 0..59) return null
            return (h * 60 + m) % (24 * 60)
        }

        fun fromJson(o: JSONObject): Rule? {
            val mode = o.optStr("mode")?.lowercase() ?: return null
            if (mode !in MODES) throw IllegalArgumentException("mode must be on, off or auto")
            val start = o.optStr("start") ?: return null
            val end = o.optStr("end") ?: return null
            if (minutes(start) == null || minutes(end) == null) throw IllegalArgumentException("times must be HH:mm")
            val arr = o.optJSONArray("days")
            val days = if (arr == null || arr.length() == 0) DAYS.toSet()
                else (0 until arr.length()).map { arr.getString(it).lowercase().take(3) }.filter { it in DAYS }.toSet()
            return Rule(mode, start, end, days)
        }

        fun listFromJson(arr: JSONArray?): List<Rule> =
            if (arr == null) emptyList() else (0 until arr.length()).mapNotNull { fromJson(arr.getJSONObject(it)) }
    }
}
