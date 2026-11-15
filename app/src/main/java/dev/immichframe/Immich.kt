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
    val acct: String,           // Account.id this asset came from
    val video: Boolean,
    val duration: Double,       // seconds, 0 = unknown / photo
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
        .put("id", id).put("account", acct).put("type", if (video) "video" else "photo")
        .put("duration", duration).put("date", date).put("place", place)
        .put("people", JSONArray(people)).put("liked", favorite)

    companion object {
        fun parse(o: JSONObject, acct: String): Asset? {
            val type = o.optStr("type") ?: "IMAGE"
            if (type != "IMAGE" && type != "VIDEO") return null
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
            return Asset(id, acct, type == "VIDEO", parseDuration(o.optStr("duration")), date,
                ex?.optStr("city"), ex?.optStr("country"), people, o.optBoolean("isFavorite", false), w, h)
        }

        /** "0:01:12.345000" → 72.345 */
        fun parseDuration(s: String?): Double {
            if (s.isNullOrBlank()) return 0.0
            return try {
                s.split(":").map { it.toDouble() }.fold(0.0) { acc, v -> acc * 60 + v }
            } catch (e: Exception) { 0.0 }
        }
    }
}

/** One Immich account (server + API key). */
class Immich(val account: Account) {
    private val jsonType = "application/json".toMediaType()

    val id get() = account.id
    val headers: Map<String, String> get() = mapOf("x-api-key" to account.apiKey)

    private fun req(path: String): Request.Builder = Request.Builder()
        .url(account.server + "/api" + path)
        .header("x-api-key", account.apiKey)
        .header("Accept", "application/json")

    private fun call(r: Request): String = http.newCall(r).execute().use { resp ->
        val b = resp.body?.string() ?: ""
        if (!resp.isSuccessful) throw IOException("${account.label}: Immich ${resp.code} on ${r.url.encodedPath}: ${b.take(200)}")
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

    /** [{id, name, count}] */
    fun albums(): List<JSONObject> {
        val arr = JSONArray(get("/albums"))
        val seen = HashSet<String>()
        return (0 until arr.length()).map { arr.getJSONObject(it) }.filter { seen.add(it.optString("id")) }.map { a ->
            JSONObject().put("id", a.optString("id")).put("name", a.optString("albumName")).put("count", a.optInt("assetCount"))
        }
    }

    /** [{id, name}] — named, non-hidden people */
    fun people(): List<JSONObject> {
        val out = ArrayList<JSONObject>()
        var page = 1
        while (page in 1..20) {
            val o = JSONObject(get("/people?withHidden=false&size=1000&page=$page"))
            val arr = o.optJSONArray("people") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val p = arr.getJSONObject(i)
                val name = p.optStr("name") ?: continue
                if (p.optBoolean("isHidden")) continue
                out.add(JSONObject().put("id", p.optString("id")).put("name", name))
            }
            page = if (o.optBoolean("hasNextPage", false)) page + 1 else 0
        }
        return out
    }

    private fun items(o: JSONObject): Pair<List<Asset>, String?> {
        val a = o.optJSONObject("assets") ?: return emptyList<Asset>() to null
        val arr = a.optJSONArray("items") ?: JSONArray()
        val list = (0 until arr.length()).mapNotNull { Asset.parse(arr.getJSONObject(it), id) }
        return list to a.optStr("nextPage")
    }

    private fun JSONObject.typed(videos: Boolean): JSONObject = if (videos) this else put("type", "IMAGE")

    /** POST /search/metadata with a filter (albumIds, personIds, isFavorite, takenAfter…), paginated. */
    fun metadata(filter: JSONObject, videos: Boolean, max: Int = 5000): List<Asset> {
        val out = ArrayList<Asset>()
        var page: String? = "1"
        while (page != null && out.size < max) {
            val body = JSONObject(filter.toString()).typed(videos)
                .put("withExif", true).put("withPeople", true)
                .put("size", 1000).put("page", page.toInt())
            val (list, next) = items(JSONObject(post("/search/metadata", body)))
            out.addAll(list)
            page = next
        }
        return out
    }

    fun random(n: Int, videos: Boolean): List<Asset> {
        val body = JSONObject().put("size", n).typed(videos).put("withExif", true).put("withPeople", true)
        val arr = JSONArray(post("/search/random", body))
        return (0 until arr.length()).mapNotNull { Asset.parse(arr.getJSONObject(it), id) }
    }

    fun smart(query: String, videos: Boolean, max: Int = 300): List<Asset> {
        val out = ArrayList<Asset>()
        var page: String? = "1"
        while (page != null && out.size < max) {
            val body = JSONObject().put("query", query).typed(videos)
                .put("withExif", true).put("size", 100).put("page", page.toInt())
            val (list, next) = items(JSONObject(post("/search/smart", body)))
            out.addAll(list)
            page = next
        }
        return out
    }

    /** "On this day" in earlier years. Uses /memories, falls back to date-range searches. */
    fun memories(videos: Boolean): List<Asset> {
        val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        try {
            val arr = JSONArray(get("/memories?for=" + iso.format(java.util.Date())))
            val out = ArrayList<Asset>()
            for (i in 0 until arr.length()) {
                val assets = arr.getJSONObject(i).optJSONArray("assets") ?: continue
                for (j in 0 until assets.length()) Asset.parse(assets.getJSONObject(j), id)?.let {
                    if (videos || !it.video) out.add(it)
                }
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
            out.addAll(metadata(JSONObject().put("takenAfter", iso.format(from.time)).put("takenBefore", iso.format(to.time)), videos, 500))
        }
        return out
    }

    fun asset(assetId: String): Asset? = Asset.parse(JSONObject(get("/assets/$assetId")), id)

    fun setFavorite(assetId: String, fav: Boolean) {
        put("/assets/$assetId", JSONObject().put("isFavorite", fav))
    }

    fun videoUrl(assetId: String) = "${account.server}/api/assets/$assetId/video/playback"

    fun downloadPreview(assetId: String, dest: File) {
        val r = req("/assets/$assetId/thumbnail?size=preview").header("Accept", "*/*").get().build()
        http.newCall(r).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("thumbnail ${resp.code} for $assetId")
            val body = resp.body ?: throw IOException("empty thumbnail for $assetId")
            dest.outputStream().use { body.byteStream().copyTo(it) }
        }
    }

    companion object {
        val http: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }
}
