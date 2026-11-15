package dev.immichframe

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
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

    /** Immich accounts (one per API key; may be different servers). */
    var accounts: List<Account>
        get() {
            val raw = p.getString("accounts", null)
            if (raw == null) {
                // migrate the single server/apiKey from 0.1/0.2
                val s = str("server", ""); val k = str("apiKey", "")
                if (s.isEmpty() || k.isEmpty()) return emptyList()
                val migrated = listOf(Account(Account.newId(), "Immich", Account.clean(s), k))
                accounts = migrated
                return migrated
            }
            return try {
                val arr = JSONArray(raw)
                (0 until arr.length()).map { Account.fromJson(arr.getJSONObject(it)) }
            } catch (e: Exception) { emptyList() }
        }
        set(v) = put("accounts", JSONArray().apply { v.forEach { put(it.toJson(withKey = true)) } }.toString())

    fun upsertAccount(a: Account) {
        val list = accounts.toMutableList()
        val i = list.indexOfFirst { it.id == a.id }
        if (i >= 0) list[i] = a else list.add(a)
        accounts = list
    }

    fun removeAccount(id: String) { accounts = accounts.filter { it.id != id } }

    /** Sleep when nobody is around (mic loudness + touches), wake on sound. */
    var presence: Boolean
        get() = p.getBoolean("presence", false)
        set(v) = put("presence", v)
    /** Time rules (on / off / auto). Migrates the old single "night off" window. */
    var schedule: List<Rule>
        get() {
            val raw = p.getString("schedule", null)
            if (raw == null) {
                val legacy = if (nightEnabled) listOf(Rule("off", nightStart, nightEnd, Rule.DAYS.toSet())) else emptyList()
                schedule = legacy
                return legacy
            }
            return try { Rule.listFromJson(JSONArray(raw)) } catch (e: Exception) { emptyList() }
        }
        set(v) = put("schedule", JSONArray().apply { v.forEach { put(it.toJson()) } }.toString())

    /** How long the screen stays on after someone was noticed (sound or touch). */
    var idleMinutes: Int
        get() = p.getInt("idleMinutes", 10)
        set(v) = put("idleMinutes", v.coerceIn(1, 720))
    var sensitivity: Int
        get() = p.getInt("sensitivity", 5)
        set(v) = put("sensitivity", v.coerceIn(1, 10))

    var videos: Boolean
        get() = p.getBoolean("videos", true)
        set(v) = put("videos", v)
    var videoSound: Boolean
        get() = p.getBoolean("videoSound", false)
        set(v) = put("videoSound", v)
    var maxVideo: Int
        get() = p.getInt("maxVideo", 60)
        set(v) = put("maxVideo", v.coerceIn(5, 600))
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

    val configured: Boolean get() = accounts.any { it.server.isNotEmpty() && it.apiKey.isNotEmpty() }
}

data class Account(val id: String, val label: String, val server: String, val apiKey: String) {
    fun toJson(withKey: Boolean = false): JSONObject = JSONObject()
        .put("id", id).put("label", label).put("server", server).put("hasKey", apiKey.isNotEmpty())
        .apply { if (withKey) put("apiKey", apiKey) }

    companion object {
        fun clean(server: String) = server.trim().trimEnd('/').removeSuffix("/api").trimEnd('/')

        fun newId(): String {
            val chars = "abcdefghjkmnpqrstuvwxyz23456789"
            val r = java.util.Random()
            return (1..6).map { chars[r.nextInt(chars.length)] }.joinToString("")
        }

        fun fromJson(o: JSONObject) = Account(
            o.optStr("id") ?: newId(), o.optStr("label") ?: "Immich",
            clean(o.optStr("server") ?: ""), o.optStr("apiKey") ?: "",
        )
    }
}
