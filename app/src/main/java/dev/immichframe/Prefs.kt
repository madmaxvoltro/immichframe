package dev.immichframe

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject

fun JSONObject.optStr(k: String): String? =
    if (has(k) && !isNull(k)) getString(k).takeIf { it.isNotEmpty() } else null

/** What the frame is playing. type: all | album | person | favorites | memories | search */
data class Source(
    val type: String = "all",
    val id: String? = null,
    val name: String = "All photos",
    val query: String? = null,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("type", type).put("id", id).put("name", name).put("query", query)

    companion object {
        fun fromJson(o: JSONObject?): Source {
            if (o == null) return Source()
            val type = o.optStr("type") ?: "all"
            return Source(type, o.optStr("id"), o.optStr("name") ?: defaultName(type), o.optStr("query"))
        }

        fun parse(s: String?): Source = try {
            fromJson(JSONObject(s ?: ""))
        } catch (e: Exception) {
            Source()
        }

        fun defaultName(type: String) = when (type) {
            "favorites" -> "Favorites"
            "memories" -> "On this day"
            else -> "All photos"
        }
    }
}

class Prefs(ctx: Context) {
    private val p: SharedPreferences = ctx.getSharedPreferences("frame", Context.MODE_PRIVATE)

    private fun str(k: String, d: String): String = p.getString(k, d) ?: d
    private fun put(k: String, v: Any?) {
        val e = p.edit()
        when (v) {
            is String -> e.putString(k, v)
            is Int -> e.putInt(k, v)
            is Boolean -> e.putBoolean(k, v)
            null -> e.remove(k)
        }
        e.apply()
    }

    var server: String
        get() = str("server", "").trim().trimEnd('/')
        set(v) = put("server", v.trim().trimEnd('/').removeSuffix("/api"))
    var apiKey: String
        get() = str("apiKey", "")
        set(v) = put("apiKey", v.trim())
    var frameName: String
        get() = str("name", "Photo frame")
        set(v) = put("name", v.trim())
    var interval: Int
        get() = p.getInt("interval", 30)
        set(v) = put("interval", v.coerceIn(5, 3600))
    var smart: Boolean
        get() = p.getBoolean("smart", true)
        set(v) = put("smart", v)
    var pairing: Boolean
        get() = p.getBoolean("pairing", true)
        set(v) = put("pairing", v)
    var showClock: Boolean
        get() = p.getBoolean("ovClock", true)
        set(v) = put("ovClock", v)
    var showWeather: Boolean
        get() = p.getBoolean("ovWeather", true)
        set(v) = put("ovWeather", v)
    var showInfo: Boolean
        get() = p.getBoolean("ovInfo", true)
        set(v) = put("ovInfo", v)
    var showAlbum: Boolean
        get() = p.getBoolean("ovAlbum", true)
        set(v) = put("ovAlbum", v)
    var nightEnabled: Boolean
        get() = p.getBoolean("night", false)
        set(v) = put("night", v)
    var nightStart: String
        get() = str("nightStart", "23:00")
        set(v) = put("nightStart", v.trim())
    var nightEnd: String
        get() = str("nightEnd", "07:00")
        set(v) = put("nightEnd", v.trim())
    var lat: String
        get() = str("lat", "")
        set(v) = put("lat", v.trim())
    var lon: String
        get() = str("lon", "")
        set(v) = put("lon", v.trim())

    /** Saved/default source (temporary ones from /api/ask or `until` are not saved). */
    var source: Source
        get() = Source.parse(p.getString("source", null))
        set(v) = put("source", v.toJson().toString())

    val configured: Boolean get() = server.isNotEmpty() && apiKey.isNotEmpty()
}
