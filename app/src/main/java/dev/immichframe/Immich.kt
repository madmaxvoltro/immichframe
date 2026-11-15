package dev.immichframe

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

class Asset(
    val id: String,
    val date: String?,          // ISO, e.g. 2025-08-12T14:03:00.000Z
    val city: String?,
    val country: String?,
    val people: List<String>,
    @Volatile var favorite: Boolean,
    val width: Int,             // 0 when unknown, already corrected for EXIF rotation
    val height: Int,
) {
    /** true/false when EXIF tells us, null when unknown. */
    val portraitHint: Boolean? get() = if (width > 0 && height > 0) height > width else null
    val place: String get() = listOfNotNull(city, country).filter { it.isNotBlank() }.joinToString(", ")

    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("date", date).put("place", place)
        .put("people", JSONArray(people)).put("liked", favorite)

    companion object {
        fun parse(o: JSONObject): Asset? {
            val type = o.optStr("type")
            if (type != null && type != "IMAGE") return null
            val id = o.optStr("id") ?: return null
            val ex = o.optJSONObject("exifInfo")
            var w = ex?.optInt("exifImageWidth", 0) ?: 0
            var h = ex?.optInt("exifImageHeight", 0) ?: 0
            if (ex?.optStr("orientation") in setOf("5", "6", "7", "8")) { val t = w; w = h; h = t }
            val date = ex?.optStr("dateTimeOriginal") ?: o.optStr("localDateTime") ?: o.optStr("fileCreatedAt")
            val people = ArrayList<String>()
            o.optJSONArray("people")?.let { arr ->
                for (i in 0 until arr.length()) arr.optJSONObject(i)?.optStr("name")?.let { people.add(it) }
            }
            return Asset(id, date, ex?.optStr("city"), ex?.optStr("country"), people,
                o.optBoolean("isFavorite", false), w, h)
        }
    }
}

class Immich(private val prefs: Prefs) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()
    private val jsonType = "application/json".toMediaType()

    private fun req(path: String): Request.Builder = Request.Builder()
        .url(prefs.server + "/api" + path)
        .header("x-api-key", prefs.apiKey)
        .header("Accept", "application/json")

    private fun call(r: Request): String = http.newCall(r).execute().use { resp ->
        val b = resp.body?.string() ?: ""
        if (!resp.isSuccessful) throw IOException("Immich ${resp.code} on ${r.url.encodedPath}: ${b.take(200)}")
        b
    }

    fun get(path: String): String = call(req(path).get().build())
    fun post(path: String, body: JSONObject): String =
        call(req(path).post(body.toString().toRequestBody(jsonType)).build())
    fun put(path: String, body: JSONObject): String =
        call(req(path).put(body.toString().toRequestBody(jsonType)).build())

    /** Returns "name (email)" if the server + key work. */
    fun ping(): String {
        val me = JSONObject(get("/users/me"))
        return "${me.optString("name")} (${me.optString("email")})"
    }

    fun albums(): JSONArray {
        val arr = JSONArray(get("/albums"))
        val out = JSONArray()
        val seen = HashSet<String>()
        for (i in 0 until arr.length()) {
            val a = arr.getJSONObject(i)
            val id = a.optString("id")
            if (!seen.add(id)) continue
            out.put(JSONObject().put("id", id).put("name", a.optString("albumName"))
                .put("count", a.optInt("assetCount")))
        }
        return out
    }

    fun people(): JSONArray {
        val o = JSONObject(get("/people?withHidden=false"))
        val arr = o.optJSONArray("people") ?: JSONArray()
        val out = JSONArray()
        for (i in 0 until arr.length()) {
            val p = arr.getJSONObject(i)
            val name = p.optStr("name") ?: continue
            if (p.optBoolean("isHidden")) continue
            out.put(JSONObject().put("id", p.optString("id")).put("name", name))
        }
        return out
    }

    private fun items(o: JSONObject): Pair<List<Asset>, String?> {
        val a = o.optJSONObject("assets") ?: return emptyList<Asset>() to null
        val arr = a.optJSONArray("items") ?: JSONArray()
        val list = (0 until arr.length()).mapNotNull { Asset.parse(arr.getJSONObject(it)) }
        return list to a.optStr("nextPage")
    }

    /** POST /search/metadata with a filter (albumIds, personIds, isFavorite, takenAfter…), paginated. */
    fun metadata(filter: JSONObject, max: Int = 5000): List<Asset> {
        val out = ArrayList<Asset>()
        var page: String? = "1"
        while (page != null && out.size < max) {
            val body = JSONObject(filter.toString())
                .put("type", "IMAGE").put("withExif", true).put("withPeople", true)
                .put("size", 1000).put("page", page.toInt())
            val (list, next) = items(JSONObject(post("/search/metadata", body)))
            out.addAll(list)
            page = next
        }
        return out
    }

    fun random(n: Int): List<Asset> {
        val body = JSONObject().put("size", n).put("type", "IMAGE")
            .put("withExif", true).put("withPeople", true)
        val arr = JSONArray(post("/search/random", body))
        return (0 until arr.length()).mapNotNull { Asset.parse(arr.getJSONObject(it)) }
    }

    fun smart(query: String, max: Int = 300): List<Asset> {
        val out = ArrayList<Asset>()
        var page: String? = "1"
        while (page != null && out.size < max) {
            val body = JSONObject().put("query", query).put("type", "IMAGE")
                .put("withExif", true).put("size", 100).put("page", page.toInt())
            val (list, next) = items(JSONObject(post("/search/smart", body)))
            out.addAll(list)
            page = next
        }
        return out
    }

    /** "On this day" in earlier years. Uses /memories, falls back to date-range searches. */
    fun memories(): List<Asset> {
        val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        try {
            val arr = JSONArray(get("/memories?for=" + iso.format(java.util.Date())))
            val out = ArrayList<Asset>()
            for (i in 0 until arr.length()) {
                val assets = arr.getJSONObject(i).optJSONArray("assets") ?: continue
                for (j in 0 until assets.length()) Asset.parse(assets.getJSONObject(j))?.let { out.add(it) }
            }
            if (out.isNotEmpty()) return out
        } catch (e: Exception) { /* older server: fall through */ }
        val out = ArrayList<Asset>()
        val today = Calendar.getInstance()
        for (back in 1..20) {
            val from = Calendar.getInstance().apply {
                set(today.get(Calendar.YEAR) - back, today.get(Calendar.MONTH), today.get(Calendar.DAY_OF_MONTH), 0, 0, 0)
            }
            val to = (from.clone() as Calendar).apply { add(Calendar.DAY_OF_MONTH, 1) }
            out.addAll(metadata(JSONObject().put("takenAfter", iso.format(from.time)).put("takenBefore", iso.format(to.time)), 500))
        }
        return out
    }

    fun asset(id: String): Asset? = Asset.parse(JSONObject(get("/assets/$id")))

    fun setFavorite(id: String, fav: Boolean) {
        put("/assets/$id", JSONObject().put("isFavorite", fav))
    }

    fun downloadPreview(id: String, dest: File) {
        val r = req("/assets/$id/thumbnail?size=preview").header("Accept", "*/*").get().build()
        http.newCall(r).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("thumbnail ${resp.code} for $id")
            val body = resp.body ?: throw IOException("empty thumbnail for $id")
            dest.outputStream().use { body.byteStream().copyTo(it) }
        }
    }
}
