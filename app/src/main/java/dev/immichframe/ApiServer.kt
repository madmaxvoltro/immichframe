package dev.immichframe

import android.util.Log
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.FileInputStream
import java.io.InputStream
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Control API on the frame: http://<frame-ip>:8080/api/...
 * No authentication (LAN only). GET / = remote control page, GET /setup = connect to Immich.
 */
class ApiServer(private val app: App, port: Int) : NanoHTTPD(port) {
    private val engine get() = app.engine
    private val prefs get() = app.prefs

    override fun useGzipWhenAccepted(r: Response): Boolean = false

    override fun serve(s: IHTTPSession): Response {
        val r = try {
            route(s)
        } catch (e: IllegalArgumentException) {
            err(Response.Status.BAD_REQUEST, e.message ?: "bad request")
        } catch (e: Exception) {
            Log.w("ApiServer", "error on ${s.uri}", e)
            err(Response.Status.INTERNAL_ERROR, e.message ?: e.toString())
        }
        r.addHeader("Access-Control-Allow-Origin", "*")
        r.addHeader("Access-Control-Allow-Headers", "Content-Type")
        r.addHeader("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS")
        return r
    }

    private fun json(o: Any, status: Response.Status = Response.Status.OK): Response =
        newFixedLengthResponse(status, "application/json; charset=utf-8", o.toString())

    private fun err(status: Response.Status, msg: String) = json(JSONObject().put("error", msg), status)

    private fun ok(extra: JSONObject? = null) = json((extra ?: JSONObject()).put("ok", true))

    private fun readBody(s: IHTTPSession): JSONObject {
        val len = s.headers["content-length"]?.toIntOrNull() ?: 0
        if (len <= 0) return JSONObject()
        val buf = ByteArray(len)
        var off = 0
        while (off < len) {
            val n = s.inputStream.read(buf, off, len - off)
            if (n < 0) break
            off += n
        }
        val txt = String(buf, 0, off, Charsets.UTF_8).trim()
        return if (txt.isEmpty()) JSONObject() else JSONObject(txt)
    }

    private fun <T> onMain(block: suspend () -> T): T = runBlocking { withContext(Dispatchers.Main) { block() } }

    private fun test(acc: Account): JSONObject = try {
        val c = Immich(acc)
        JSONObject().put("connected", true).put("user", c.ping()).put("albums", c.albums().size).put("people", c.people().size)
    } catch (e: Exception) {
        JSONObject().put("connected", false).put("error", e.message ?: e.toString())
    }

    private fun route(s: IHTTPSession): Response {
        if (s.method == Method.OPTIONS) return newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, "")
        val path = s.uri.trimEnd('/').ifEmpty { "/" }

        if (path == "/" || path == "/index.html" || path == "/setup" || path == "/settings") {
            val file = if (path == "/setup" || path == "/settings") "setup.html" else "control.html"
            val html = app.assets.open(file).bufferedReader().use { it.readText() }
            return newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", html)
        }
        if (!path.startsWith("/api/")) return err(Response.Status.NOT_FOUND, "not found")
        // No auth on purpose: LAN-only frame, open http://<frame-ip>:8080 from any device.

        val m = s.method
        val p = path.removePrefix("/api")
        val body = if (m == Method.POST || m == Method.PUT) readBody(s) else JSONObject()
        val get = m == Method.GET
        val post = m == Method.POST

        return when {
            // ---- state
            get && p == "/status" -> json(onMain { engine.statusJson() })
            get && p == "/now" -> json(onMain { engine.nowJson() })
            get && p == "/events" -> {
                val r = newChunkedResponse(Response.Status.OK, "text/event-stream", EventStream(engine))
                r.addHeader("Cache-Control", "no-cache")
                r
            }
            get && p.startsWith("/thumb/") -> {
                val id = p.removePrefix("/thumb/")
                if (!id.matches(Regex("[A-Za-z0-9-]+"))) return err(Response.Status.BAD_REQUEST, "bad id")
                val f = app.cache.ensure(id, engine.findAsset(id)?.acct)
                newFixedLengthResponse(Response.Status.OK, "image/jpeg", FileInputStream(f), f.length())
            }

            // ---- what to show
            post && p == "/play" -> {
                val src = runBlocking { engine.resolveSource(body.optJSONObject("source") ?: body) }
                body.optInt("interval", 0).takeIf { it > 0 }?.let { prefs.interval = it }
                val n = runBlocking { engine.play(src, body.optStr("until")) }
                ok(JSONObject().put("matched", n).put("source", src.toJson()))
            }
            post && p == "/ask" -> {
                val q = body.optStr("q") ?: throw IllegalArgumentException("body needs {\"q\": \"...\"}")
                val n = runBlocking { engine.ask(q, body.optStr("until")) }
                ok(JSONObject().put("matched", n).put("playing", n > 0).put("query", q))
            }
            post && p == "/show" -> {
                val id = body.optStr("assetId") ?: throw IllegalArgumentException("body needs assetId")
                val sec = if (body.has("seconds")) body.getInt("seconds") else null
                val shown = runBlocking { engine.showAsset(id, sec) }
                if (shown) ok() else err(Response.Status.NOT_FOUND, "asset not found or not an image")
            }
            get && p == "/albums" -> json(runBlocking { engine.albums() })
            get && p == "/people" -> json(runBlocking { engine.people() })

            // ---- playback
            post && p == "/next" -> { runBlocking { engine.next(true) }; ok() }
            post && p == "/prev" -> { runBlocking { engine.prev() }; ok() }
            post && p == "/pause" -> { runBlocking { engine.setPaused(true) }; ok() }
            post && p == "/resume" -> { runBlocking { engine.setPaused(false) }; ok() }

            // ---- taste
            post && p == "/like" -> {
                val ids = runBlocking { engine.like(body.optStr("assetId"), body.optBoolean("like", true)) }
                ok(JSONObject().put("ids", JSONArray(ids)))
            }
            post && p == "/less" -> ok(JSONObject().put("ids", JSONArray(runBlocking { engine.less(body.optStr("assetId")) })))
            post && p == "/hide" -> ok(JSONObject().put("ids", JSONArray(runBlocking { engine.hide(body.optStr("assetId")) })))
            get && p == "/stats" -> json(app.stats.summary())

            // ---- Immich accounts (API keys never leave the frame)
            get && p == "/accounts" -> json(JSONArray().apply { prefs.accounts.forEach { put(it.toJson()) } })
            post && p == "/accounts" -> {
                // add, or update when "id" is given (apiKey optional on update)
                val id = body.optStr("id")
                val old = prefs.accounts.find { it.id == id }
                val server = Account.clean(body.optStr("server") ?: old?.server ?: throw IllegalArgumentException("server is required"))
                val key = body.optStr("apiKey") ?: old?.apiKey ?: throw IllegalArgumentException("apiKey is required")
                val acc = Account(old?.id ?: Account.newId(), body.optStr("label") ?: old?.label ?: "Immich", server, key)
                val res = test(acc)
                prefs.upsertAccount(acc)
                runBlocking { withContext(Dispatchers.Main) { app.engine.reload(); app.onConfigured?.invoke() } }
                ok(res.put("account", acc.toJson()))
            }
            post && p.matches(Regex("/accounts/[a-z0-9]+/test")) -> {
                val acc = prefs.accounts.find { it.id == p.split("/")[2] } ?: return err(Response.Status.NOT_FOUND, "no such account")
                json(test(acc))
            }
            (m == Method.DELETE && p.matches(Regex("/accounts/[a-z0-9]+"))) ||
                (post && p.matches(Regex("/accounts/[a-z0-9]+/delete"))) -> {
                prefs.removeAccount(p.split("/")[2])
                runBlocking { withContext(Dispatchers.Main) { app.engine.reload() } }
                ok()
            }

            // ---- frame
            get && p == "/settings" -> json(onMain { engine.settingsJson() })
            (m == Method.PUT || post) && p == "/settings" -> json(runBlocking { engine.applySettings(body) })
            post && p == "/display" -> {
                val on = if (body.has("on")) body.getBoolean("on") else null
                val b = if (body.has("brightness")) body.getInt("brightness") else null
                runBlocking { engine.setDisplay(on, b) }
                ok()
            }
            // ---- sleep / presence
            get && (p == "/sleep" || p == "/presence") -> json(onMain { engine.sleepJson() })
            post && p == "/sleep" -> {
                // {"minutes": 60} = wake up again after an hour; no minutes = until /wake, a touch or the morning
                val min = if (body.has("minutes")) body.getInt("minutes") else null
                runBlocking { engine.sleep(min, "manual") }
                ok(onMain { engine.sleepJson() })
            }
            post && p == "/wake" -> { runBlocking { engine.wake("api") }; ok(onMain { engine.sleepJson() }) }

            get && p == "/update" -> json(app.updater.statusJson())
            post && p == "/update" -> json(app.updater.checkAndInstall(body.optBoolean("install", true)))
            post && p == "/reboot" -> {
                if (s.parameters["device"]?.firstOrNull() == "1")
                    return err(Response.Status.NOT_IMPLEMENTED, "device reboot needs root; use adb reboot")
                app.restartUi()
                ok()
            }
            else -> err(Response.Status.NOT_FOUND, "no route ${m.name} /api$p")
        }
    }

    /** Server-sent events: blocks reading from a queue, pings every 15 s. */
    private class EventStream(private val engine: Engine) : InputStream() {
        private val q = LinkedBlockingQueue<ByteArray>(200)
        private var cur: ByteArray? = null
        private var pos = 0
        @Volatile private var closed = false
        private val sink: (String) -> Unit = { q.offer(it.toByteArray()) }

        init {
            engine.events.add(sink)
            q.offer("retry: 3000\n\n".toByteArray())
        }

        override fun read(): Int {
            val b = ByteArray(1)
            return if (read(b, 0, 1) <= 0) -1 else b[0].toInt() and 0xff
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (closed) return -1
            while (cur == null || pos >= cur!!.size) {
                cur = q.poll(15, TimeUnit.SECONDS) ?: ": ping\n\n".toByteArray()
                pos = 0
            }
            val c = cur!!
            val n = minOf(len, c.size - pos)
            System.arraycopy(c, pos, b, off, n)
            pos += n
            return n
        }

        override fun close() {
            closed = true
            engine.events.remove(sink)
        }
    }
}
