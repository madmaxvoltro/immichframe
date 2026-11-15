package dev.immichframe

import android.graphics.Bitmap
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.Calendar
import java.util.Random
import java.util.concurrent.CopyOnWriteArrayList

class Slide(val assets: List<Asset>, val bitmaps: List<Bitmap>)

/**
 * The brain of the frame. All state lives on the main thread; public suspend functions
 * hop to Main themselves so the HTTP API and the UI can call them from anywhere.
 */
class Engine(private val app: App) {
    interface Listener {
        fun onSlide(slide: Slide, animate: Boolean)
        fun onState()
        fun onDisplay()
    }

    private val tag = "Engine"
    private val prefs get() = app.prefs
    private val immich get() = app.immich
    private val stats get() = app.stats
    private val cache get() = app.cache
    private val main = Dispatchers.Main.immediate
    private val io = Dispatchers.IO

    val scope = CoroutineScope(SupervisorJob() + main)
    private val lock = Mutex()
    private val rnd = Random()

    var listener: Listener? = null
    var source: Source = Source(); private set
    var pool: List<Asset> = emptyList(); private set
    private var poolAt = 0L
    var status = "starting"; private set
    var lastError: String? = null; private set
    var current: Slide? = null; private set
    var shownAt = 0L; private set
    var paused = false; private set
    var screenW = 1280; private set
    var screenH = 800; private set
    val landscape get() = screenW >= screenH
    var displayOn = true; private set
    var brightness = -1; private set          // -1 = system default, else 0..100
    var nightActive = false; private set
    var weatherTemp: Int? = null; private set
    var weatherDesc: String? = null; private set

    private val back = ArrayList<List<Asset>>()
    private val fwd = ArrayList<List<Asset>>()
    private var tick: Job? = null
    private var prefetch: Deferred<Slide?>? = null
    private var started = false
    private var pinUntil = 0L
    private var revertJob: Job? = null
    private var albumCache: Pair<Long, JSONArray>? = null
    private var peopleCache: Pair<Long, JSONArray>? = null

    /** Server-sent-event sinks registered by the API server. */
    val events = CopyOnWriteArrayList<(String) -> Unit>()

    // ---------------------------------------------------------------- lifecycle

    fun ensureStarted() {
        if (started) return
        started = true
        source = prefs.source
        scope.launch { lock.withLock { loadPool(true) }; advance() }
        scope.launch { while (true) { checkNight(); delay(30_000) } }
        scope.launch { while (true) { refreshWeather(); delay(30 * 60_000L) } }
    }

    /** Called after the setup screen saved new settings. */
    fun reload() {
        if (!started) { ensureStarted(); return }
        scope.launch {
            source = prefs.source
            fwd.clear(); invalidate()
            albumCache = null; peopleCache = null
            lock.withLock { loadPool(true) }
            advance()
            refreshWeather()
        }
    }

    fun setScreen(w: Int, h: Int) {
        if (w <= 0 || h <= 0) return
        val wasLand = landscape
        screenW = w; screenH = h
        if (wasLand == landscape) return
        invalidate()
        val c = current ?: return
        scope.launch {
            lock.withLock {
                val list = if (landscape) c.assets else c.assets.take(1)
                render(list)?.let { show(it, push = false, animate = false) }
            }
        }
    }

    // ---------------------------------------------------------------- loading + picking

    private suspend fun loadPool(force: Boolean) {
        if (!prefs.configured) {
            status = "setup"; pool = emptyList(); notifyState(); return
        }
        if (!force && pool.isNotEmpty() && System.currentTimeMillis() - poolAt < 3_600_000) return
        status = "loading"; notifyState()
        val src = source
        try {
            val list = withContext(io) { fetch(src) }
            pool = list.distinctBy { it.id }
            poolAt = System.currentTimeMillis()
            status = if (pool.isEmpty()) "empty" else "playing"
            lastError = null
        } catch (e: Exception) {
            Log.w(tag, "load failed", e)
            lastError = e.message ?: e.toString()
            status = "error"
        }
        notifyState()
        emit("state", statusJson())
    }

    private fun fetch(s: Source): List<Asset> = when (s.type) {
        "album" -> immich.metadata(JSONObject().put("albumIds", JSONArray().put(s.id)))
        "person" -> immich.metadata(JSONObject().put("personIds", JSONArray().put(s.id)))
        "favorites" -> immich.metadata(JSONObject().put("isFavorite", true))
        "memories" -> immich.memories()
        "search" -> immich.smart(s.query ?: s.name)
        else -> immich.random(500)
    }

    private suspend fun advance() = lock.withLock {
        if (fwd.isNotEmpty()) {
            val a = fwd.removeAt(fwd.size - 1)
            val s = render(a)
            if (s != null) { show(s, push = true, animate = true); return@withLock }
        }
        if (prefs.configured && (pool.isEmpty() || source.type == "all")) loadPool(pool.isEmpty())
        val pf = prefetch
        prefetch = null
        val slide = (if (pf != null) runCatching { pf.await() }.getOrNull() else null) ?: buildNext()
        if (slide == null) {
            if (status == "playing" && pool.isNotEmpty()) notifyState()
            schedule(20_000)
            return@withLock
        }
        show(slide, push = true, animate = true)
    }

    private suspend fun buildNext(): Slide? {
        val exclude = HashSet<String>()
        current?.assets?.forEach { exclude.add(it.id) }
        val p = pool
        if (p.isEmpty()) return null
        val pair = landscape && prefs.pairing
        val smart = prefs.smart
        val w = screenW
        val h = screenH
        return withContext(io) {
            try {
                val picks = choose(p, exclude, pair, smart)
                if (picks.isEmpty()) null else renderBlocking(picks, w, h)
            } catch (e: Exception) {
                Log.w(tag, "buildNext failed", e)
                withContext(main) { lastError = e.message }
                null
            }
        }
    }

    private fun choose(pool: List<Asset>, exclude: Set<String>, pair: Boolean, smart: Boolean): List<Asset> {
        val rows = stats.all()
        val liked = stats.likedPeople()
        val now = System.currentTimeMillis()
        val w = DoubleArray(pool.size) { Shuffle.weight(pool[it], rows[pool[it].id], liked, now, smart) }
        val first = Shuffle.pick(pool, w, exclude, rnd) ?: return emptyList()
        if (!pair || first.portraitHint == false) return listOf(first)
        val (fw, fh) = cache.bounds(cache.ensure(first.id))
        if (fh <= fw) return listOf(first)
        val ex = HashSet(exclude).apply { add(first.id) }
        repeat(10) {
            val c = Shuffle.pick(pool, w, ex, rnd) ?: return listOf(first)
            ex.add(c.id)
            if (c.portraitHint == false) return@repeat
            val (cw, ch) = cache.bounds(cache.ensure(c.id))
            if (ch > cw) return listOf(first, c)
        }
        return listOf(first)
    }

    private suspend fun render(list: List<Asset>): Slide? {
        val w = screenW
        val h = screenH
        return withContext(io) {
            try { renderBlocking(list, w, h) } catch (e: Exception) { Log.w(tag, "render failed", e); null }
        }
    }

    private fun renderBlocking(list: List<Asset>, w: Int, h: Int): Slide {
        val pair = list.size >= 2 && w >= h
        val use = if (pair) list.take(2) else list.take(1)
        val tw = if (pair) w / 2 else w
        val bms = use.map { cache.decode(cache.ensure(it.id), tw, h) ?: throw IOException("cannot decode ${it.id}") }
        return Slide(use, bms)
    }

    private fun show(slide: Slide, push: Boolean, animate: Boolean) {
        val prev = current
        if (push && prev != null) {
            back.add(prev.assets)
            if (back.size > 60) back.removeAt(0)
        }
        current = slide
        shownAt = System.currentTimeMillis()
        if (status != "setup") status = "playing"
        listener?.onSlide(slide, animate)
        val ids = slide.assets.map { it.id }
        scope.launch(io) { ids.forEach { runCatching { stats.shown(it) } } }
        emit("photo", nowJson())
        notifyState()
        schedule(null)
        startPrefetch()
    }

    private fun schedule(override: Long?) {
        tick?.cancel()
        if (paused) return
        val now = System.currentTimeMillis()
        val ms = override ?: maxOf(prefs.interval * 1000L, pinUntil - now)
        tick = scope.launch {
            delay(ms)
            if (!displayOn || nightActive) { schedule(60_000); return@launch }
            pinUntil = 0
            advance()
        }
    }

    private fun startPrefetch() {
        prefetch?.cancel()
        prefetch = null
        if (fwd.isNotEmpty() || pool.isEmpty()) return
        prefetch = scope.async { buildNext() }
    }

    private fun invalidate() {
        prefetch?.cancel()
        prefetch = null
    }

    // ---------------------------------------------------------------- commands (callable from any thread)

    suspend fun next(user: Boolean = true) = withContext(main) {
        val c = current
        if (user && c != null && System.currentTimeMillis() - shownAt < 5_000) {
            val ids = c.assets.map { it.id }
            withContext(io) { ids.forEach { stats.skip(it) } }
        }
        tick?.cancel()
        pinUntil = 0
        advance()
    }

    suspend fun prev() = withContext(main) {
        lock.withLock {
            if (back.isEmpty()) return@withLock
            val a = back.removeAt(back.size - 1)
            current?.let { fwd.add(it.assets) }
            invalidate()
            render(a)?.let { show(it, push = false, animate = true) }
        }
    }

    suspend fun setPaused(p: Boolean) = withContext(main) {
        paused = p
        if (p) tick?.cancel() else schedule(null)
        notifyState()
        emit("state", statusJson())
    }

    private fun targets(assetId: String?): List<Asset> {
        if (assetId == null) return current?.assets ?: emptyList()
        val a = current?.assets?.find { it.id == assetId } ?: pool.find { it.id == assetId }
        return listOf(a ?: Asset(assetId, null, null, null, emptyList(), false, 0, 0))
    }

    suspend fun like(assetId: String?, value: Boolean = true): List<String> = withContext(main) {
        val t = targets(assetId)
        t.forEach { it.favorite = value }
        withContext(io) {
            t.forEach {
                stats.setLike(it.id, value, it.people)
                try { immich.setFavorite(it.id, value) } catch (e: Exception) { Log.w(tag, "favorite sync failed", e) }
            }
        }
        invalidate(); startPrefetch()
        notifyState()
        emit("liked", JSONObject().put("ids", JSONArray(t.map { it.id })).put("liked", value))
        t.map { it.id }
    }

    suspend fun less(assetId: String?): List<String> = withContext(main) {
        val t = targets(assetId)
        withContext(io) { t.forEach { stats.less(it.id) } }
        invalidate(); startPrefetch()
        t.map { it.id }
    }

    suspend fun hide(assetId: String?): List<String> = withContext(main) {
        val t = targets(assetId)
        withContext(io) { t.forEach { stats.hide(it.id) } }
        val ids = t.map { it.id }
        back.removeAll { l -> l.any { it.id in ids } }
        fwd.removeAll { l -> l.any { it.id in ids } }
        invalidate()
        if (current?.assets?.any { it.id in ids } == true) next(user = false) else startPrefetch()
        ids
    }

    suspend fun showAsset(assetId: String, seconds: Int?): Boolean = withContext(main) {
        val a = try { withContext(io) { immich.asset(assetId) } } catch (e: Exception) { null } ?: return@withContext false
        lock.withLock {
            val s = render(listOf(a)) ?: return@withLock false
            pinUntil = if (seconds != null && seconds > 0) System.currentTimeMillis() + seconds * 1000L else 0
            fwd.clear(); invalidate()
            show(s, push = true, animate = true)
            true
        }
    }

    /** Switch source. Returns how many photos matched. `until` = "HH:mm" → revert to the saved source then. */
    suspend fun play(src: Source, until: String? = null, persist: Boolean = until == null): Int = withContext(main) {
        source = src
        if (persist) prefs.source = src
        revertJob?.cancel()
        if (until != null) {
            val ms = msUntil(until)
            if (ms != null) revertJob = scope.launch { delay(ms); play(prefs.source) }
        }
        fwd.clear(); invalidate(); tick?.cancel()
        lock.withLock { loadPool(true) }
        emit("source", src.toJson())
        advance()
        notifyState()
        pool.size
    }

    suspend fun ask(q: String, until: String?): Int =
        play(Source("search", null, "“$q”", q), until, persist = false)

    suspend fun setDisplay(on: Boolean?, bright: Int?) = withContext(main) {
        if (on != null) displayOn = on
        if (bright != null) brightness = bright.coerceIn(-1, 100)
        listener?.onDisplay()
        if (displayOn && !paused && tick?.isActive != true) schedule(null)
        emit("display", JSONObject().put("on", displayOn).put("brightness", brightness))
    }

    suspend fun albums(): JSONArray {
        albumCache?.let { if (System.currentTimeMillis() - it.first < 300_000) return it.second }
        val a = withContext(io) { immich.albums() }
        albumCache = System.currentTimeMillis() to a
        return a
    }

    suspend fun people(): JSONArray {
        peopleCache?.let { if (System.currentTimeMillis() - it.first < 300_000) return it.second }
        val a = withContext(io) { immich.people() }
        peopleCache = System.currentTimeMillis() to a
        return a
    }

    /** Resolve {type, id?, name?, query?}; album/person can be given by name only. */
    suspend fun resolveSource(o: JSONObject): Source {
        val type = o.optStr("type") ?: "all"
        var id = o.optStr("id")
        var name = o.optStr("name")
        if ((type == "album" || type == "person") && id == null) {
            val n = name ?: throw IllegalArgumentException("$type needs id or name")
            val list = if (type == "album") albums() else people()
            val hit = (0 until list.length()).map { list.getJSONObject(it) }
                .let { l -> l.find { it.optString("name").equals(n, true) } ?: l.find { it.optString("name").contains(n, true) } }
                ?: throw IllegalArgumentException("no $type named '$n'")
            id = hit.getString("id"); name = hit.getString("name")
        }
        return Source(type, id, name ?: Source.defaultName(type), o.optStr("query"))
    }

    // ---------------------------------------------------------------- periodic

    private fun minutes(hhmm: String): Int? {
        val p = hhmm.split(":")
        val h = p.getOrNull(0)?.trim()?.toIntOrNull() ?: return null
        val m = p.getOrNull(1)?.trim()?.toIntOrNull() ?: 0
        return h * 60 + m
    }

    private fun msUntil(hhmm: String): Long? {
        val target = minutes(hhmm) ?: return null
        val c = Calendar.getInstance()
        val nowMin = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE)
        var diff = target - nowMin
        if (diff <= 0) diff += 24 * 60
        return diff * 60_000L - c.get(Calendar.SECOND) * 1000L
    }

    private fun checkNight() {
        var active = false
        if (prefs.nightEnabled) {
            val s = minutes(prefs.nightStart)
            val e = minutes(prefs.nightEnd)
            if (s != null && e != null) {
                val c = Calendar.getInstance()
                val n = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE)
                active = if (s <= e) n in s until e else n >= s || n < e
            }
        }
        if (active != nightActive) {
            nightActive = active
            listener?.onDisplay()
            if (!active && !paused) schedule(1_000)
            emit("display", JSONObject().put("night", active))
        }
    }

    private suspend fun refreshWeather() {
        val lat = prefs.lat
        val lon = prefs.lon
        if (!prefs.showWeather || lat.isBlank() || lon.isBlank()) {
            weatherTemp = null; weatherDesc = null; notifyState(); return
        }
        try {
            val r = withContext(io) { Weather.fetch(lat, lon) }
            weatherTemp = r?.first; weatherDesc = r?.second
        } catch (e: Exception) {
            Log.w(tag, "weather failed", e)
        }
        notifyState()
    }

    // ---------------------------------------------------------------- json

    private fun notifyState() { listener?.onState() }

    fun emit(type: String, data: JSONObject) {
        if (events.isEmpty()) return
        val s = "event: $type\ndata: $data\n\n"
        events.forEach { runCatching { it(s) } }
    }

    fun nowJson(): JSONObject {
        val c = current
        return JSONObject()
            .put("assets", JSONArray().apply { c?.assets?.forEach { put(it.toJson()) } })
            .put("source", source.toJson())
            .put("shownAt", shownAt)
            .put("paused", paused)
            .put("interval", prefs.interval)
            .put("thumbnail", c?.assets?.firstOrNull()?.let { "/api/thumb/${it.id}" })
    }

    fun statusJson(): JSONObject = JSONObject()
        .put("name", prefs.frameName)
        .put("status", status)
        .put("error", lastError)
        .put("configured", prefs.configured)
        .put("source", source.toJson())
        .put("poolSize", pool.size)
        .put("paused", paused)
        .put("orientation", if (landscape) "landscape" else "portrait")
        .put("screen", "${screenW}x$screenH")
        .put("displayOn", displayOn)
        .put("night", nightActive)
        .put("brightness", brightness)
        .put("weather", weatherTemp?.let { JSONObject().put("temp", it).put("desc", weatherDesc) })
        .put("version", BuildConfig.VERSION_NAME)

    fun settingsJson(): JSONObject = JSONObject()
        .put("name", prefs.frameName)
        .put("interval", prefs.interval)
        .put("smart", prefs.smart)
        .put("pairing", prefs.pairing)
        .put("overlays", JSONObject().put("clock", prefs.showClock).put("weather", prefs.showWeather)
            .put("info", prefs.showInfo).put("album", prefs.showAlbum))
        .put("night", JSONObject().put("enabled", prefs.nightEnabled).put("start", prefs.nightStart).put("end", prefs.nightEnd))
        .put("lat", prefs.lat).put("lon", prefs.lon)
        .put("defaultSource", prefs.source.toJson())

    suspend fun applySettings(o: JSONObject) = withContext(main) {
        o.optStr("name")?.let { prefs.frameName = it }
        if (o.has("interval")) prefs.interval = o.getInt("interval")
        if (o.has("smart")) prefs.smart = o.getBoolean("smart")
        if (o.has("pairing")) { prefs.pairing = o.getBoolean("pairing"); invalidate(); startPrefetch() }
        o.optJSONObject("overlays")?.let { ov ->
            if (ov.has("clock")) prefs.showClock = ov.getBoolean("clock")
            if (ov.has("weather")) prefs.showWeather = ov.getBoolean("weather")
            if (ov.has("info")) prefs.showInfo = ov.getBoolean("info")
            if (ov.has("album")) prefs.showAlbum = ov.getBoolean("album")
        }
        o.optJSONObject("night")?.let { n ->
            if (n.has("enabled")) prefs.nightEnabled = n.getBoolean("enabled")
            n.optStr("start")?.let { prefs.nightStart = it }
            n.optStr("end")?.let { prefs.nightEnd = it }
            checkNight()
        }
        o.optStr("lat")?.let { prefs.lat = it }
        o.optStr("lon")?.let { prefs.lon = it }
        if (o.has("lat") || o.has("lon") || o.optJSONObject("overlays")?.has("weather") == true) refreshWeather()
        if (!paused) schedule(null)
        notifyState()
        settingsJson()
    }
}
