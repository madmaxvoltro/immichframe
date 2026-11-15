package dev.immichframe

import android.animation.ObjectAnimator
import android.app.Activity
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.LinearInterpolator
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import kotlinx.coroutines.launch
import org.json.JSONArray
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.math.abs

class SlideshowActivity : Activity(), Engine.Listener {
    private val app get() = App.instance
    private val engine get() = app.engine
    private val ui = Handler(Looper.getMainLooper())

    private lateinit var stage: FrameLayout
    private lateinit var albumChip: View
    private lateinit var albumName: TextView
    private lateinit var weatherBox: View
    private lateinit var temp: TextView
    private lateinit var weatherDesc: TextView
    private lateinit var clockBox: LinearLayout
    private lateinit var infoBox: LinearLayout
    private lateinit var place: TextView
    private lateinit var meta: TextView
    private lateinit var likedTag: TextView
    private lateinit var progressTrack: View
    private lateinit var progressFill: View
    private lateinit var message: TextView
    private lateinit var controls: View
    private lateinit var btnSource: Button
    private lateinit var btnPause: ImageButton
    private lateinit var btnLike: Button
    private lateinit var picker: View
    private lateinit var tabs: LinearLayout
    private lateinit var askField: EditText
    private lateinit var list: ListView
    private lateinit var nightCover: View

    private var progressAnim: ObjectAnimator? = null
    private val hideControls = Runnable { setControls(false) }
    private var tab = "Albums"
    private var rows: List<PickRow> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_slideshow)
        bind()
        applyOrientation()
        if (!app.prefs.configured) openSetup()
    }

    private fun bind() {
        stage = findViewById(R.id.stage)
        albumChip = findViewById(R.id.albumChip)
        albumName = findViewById(R.id.albumName)
        weatherBox = findViewById(R.id.weatherBox)
        temp = findViewById(R.id.temp)
        weatherDesc = findViewById(R.id.weatherDesc)
        clockBox = findViewById(R.id.clockBox)
        infoBox = findViewById(R.id.infoBox)
        place = findViewById(R.id.place)
        meta = findViewById(R.id.meta)
        likedTag = findViewById(R.id.likedTag)
        progressTrack = findViewById(R.id.progressTrack)
        progressFill = findViewById(R.id.progressFill)
        message = findViewById(R.id.message)
        controls = findViewById(R.id.controls)
        btnSource = findViewById(R.id.btnSource)
        btnPause = findViewById(R.id.btnPause)
        btnLike = findViewById(R.id.btnLike)
        picker = findViewById(R.id.picker)
        tabs = findViewById(R.id.tabs)
        askField = findViewById(R.id.askField)
        list = findViewById(R.id.list)
        nightCover = findViewById(R.id.nightCover)

        stage.setOnClickListener { setControls(true) }
        message.setOnClickListener { if (!app.prefs.configured) openSetup() else setControls(true) }
        controls.setOnClickListener { setControls(false) }
        nightCover.setOnClickListener { /* swallow taps while screen is "off" */ }

        findViewById<View>(R.id.btnPrev).setOnClickListener { poke(); engine.scope.launch { engine.prev() } }
        findViewById<View>(R.id.btnNext).setOnClickListener { poke(); engine.scope.launch { engine.next(true) } }
        btnPause.setOnClickListener { poke(); engine.scope.launch { engine.setPaused(!engine.paused) } }
        btnLike.setOnClickListener {
            poke()
            val liked = engine.current?.assets?.all { it.favorite } == true
            engine.scope.launch { engine.like(null, !liked) }
        }
        findViewById<View>(R.id.btnLess).setOnClickListener {
            poke(); engine.scope.launch { engine.less(null); toast("Okay, fewer like this") }
        }
        findViewById<View>(R.id.btnHide).setOnClickListener {
            setControls(false); engine.scope.launch { engine.hide(null) }
        }
        findViewById<View>(R.id.btnSettings).setOnClickListener { openSetup() }
        btnSource.setOnClickListener { openPicker() }
        findViewById<View>(R.id.btnClosePicker).setOnClickListener { closePicker() }
        findViewById<View>(R.id.btnAsk).setOnClickListener { ask() }
        askField.setOnEditorActionListener { _, id, _ ->
            if (id == EditorInfo.IME_ACTION_SEARCH || id == EditorInfo.IME_ACTION_DONE) { ask(); true } else false
        }
        list.setOnItemClickListener { _, _, pos, _ -> rows.getOrNull(pos)?.let { choose(it) } }
    }

    override fun onResume() {
        super.onResume()
        immersive()
        engine.listener = this
        reportScreen()
        engine.ensureStarted()
        engine.current?.let { onSlide(it, false) }
        onState()
        onDisplay()
    }

    override fun onPause() {
        super.onPause()
        if (engine.listener === this) engine.listener = null
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) immersive()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyOrientation()
        stage.post { reportScreen() }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            picker.visibility == View.VISIBLE -> closePicker()
            controls.visibility == View.VISIBLE -> setControls(false)
            // we are the home screen: never exit
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_NEXT -> { engine.scope.launch { engine.next(true) }; return true }
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_PREVIOUS -> { engine.scope.launch { engine.prev() }; return true }
        }
        return super.onKeyDown(keyCode, event)
    }

    @Suppress("DEPRECATION")
    private fun immersive() {
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_FULLSCREEN
            or View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN)
    }

    private fun reportScreen() {
        val dm = resources.displayMetrics
        val w = if (stage.width > 0) stage.width else dm.widthPixels
        val h = if (stage.height > 0) stage.height else dm.heightPixels
        engine.setScreen(w, h)
    }

    private fun isLandscape() = resources.configuration.orientation != Configuration.ORIENTATION_PORTRAIT

    /** Landscape: clock bottom-left, info bottom-right. Portrait: clock + weather on top, info + album at the bottom. */
    private fun applyOrientation() {
        val land = isLandscape()
        fun lp(v: View) = v.layoutParams as FrameLayout.LayoutParams
        lp(clockBox).apply {
            gravity = if (land) Gravity.BOTTOM or Gravity.START else Gravity.TOP or Gravity.START
            topMargin = if (land) 0 else dp(44); bottomMargin = if (land) dp(40) else 0
        }
        lp(weatherBox).apply { topMargin = if (land) dp(28) else dp(64) }
        lp(albumChip).apply {
            gravity = if (land) Gravity.TOP or Gravity.START else Gravity.BOTTOM or Gravity.END
            topMargin = if (land) dp(32) else 0; bottomMargin = if (land) 0 else dp(48)
        }
        lp(infoBox).apply {
            gravity = if (land) Gravity.BOTTOM or Gravity.END else Gravity.BOTTOM or Gravity.START
            marginStart = dp(40); marginEnd = dp(40)
        }
        infoBox.gravity = if (land) Gravity.END else Gravity.START
        findViewById<View>(R.id.scrimTop).layoutParams.height = if (land) dp(160) else dp(380)
        (findViewById<View>(R.id.tasteRow) as LinearLayout).orientation =
            if (land) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        for (id in intArrayOf(R.id.btnLess, R.id.btnHide)) {
            (findViewById<View>(id).layoutParams as LinearLayout.LayoutParams).apply {
                marginStart = if (land) dp(16) else 0; topMargin = if (land) 0 else dp(12)
            }
        }
        clockBox.requestLayout()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    // ---------------------------------------------------------------- Engine.Listener

    override fun onSlide(slide: Slide, animate: Boolean) {
        val v = buildSlideView(slide)
        val old = (0 until stage.childCount).map { stage.getChildAt(it) }
        stage.addView(v, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        if (animate && old.isNotEmpty()) {
            v.alpha = 0f
            v.animate().alpha(1f).setDuration(900).withEndAction { old.forEach { stage.removeView(it) } }.start()
        } else {
            old.forEach { stage.removeView(it) }
        }
        updateInfo(slide)
        startProgress()
    }

    override fun onState() {
        val p = app.prefs
        albumName.text = engine.source.name
        btnSource.text = engine.source.name
        albumChip.visibility = if (p.showAlbum && engine.current != null) View.VISIBLE else View.GONE
        clockBox.visibility = if (p.showClock) View.VISIBLE else View.GONE
        val t = engine.weatherTemp
        weatherBox.visibility = if (p.showWeather && t != null) View.VISIBLE else View.GONE
        if (t != null) { temp.text = "$t°"; weatherDesc.text = engine.weatherDesc ?: "" }

        btnPause.setImageResource(if (engine.paused) R.drawable.ic_play else R.drawable.ic_pause)
        btnPause.contentDescription = if (engine.paused) "Play" else "Pause"
        if (engine.paused) progressAnim?.pause() else progressAnim?.resume()

        val liked = engine.current?.assets?.let { a -> a.isNotEmpty() && a.all { it.favorite } } == true
        btnLike.text = if (liked) "Liked" else "Like"
        btnLike.setBackgroundResource(if (liked) R.drawable.bg_pill_liked else R.drawable.bg_pill_outline)
        btnLike.setTextColor(if (liked) Color.parseColor("#0B0B0C") else Color.parseColor("#F4F1EA"))
        btnLike.setCompoundDrawablesRelativeWithIntrinsicBounds(
            getDrawable(if (liked) R.drawable.ic_heart_fill else R.drawable.ic_heart)?.mutate()?.apply {
                setTint(if (liked) Color.parseColor("#0B0B0C") else Color.parseColor("#F4F1EA"))
            }, null, null, null)
        engine.current?.let { updateInfo(it) }

        val msg = when {
            !p.configured -> "Tap to connect this frame to Immich"
            engine.current != null -> null
            engine.status == "loading" || engine.status == "starting" -> "Loading photos…"
            engine.status == "empty" -> "No photos in “${engine.source.name}”.\nTap to pick something else."
            engine.status == "error" -> "Can't reach Immich\n${engine.lastError ?: ""}"
            else -> null
        }
        message.text = msg ?: ""
        message.visibility = if (msg == null) View.GONE else View.VISIBLE
    }

    override fun onDisplay() {
        val off = !engine.displayOn || engine.nightActive
        nightCover.visibility = if (off) View.VISIBLE else View.GONE
        val lp = window.attributes
        lp.screenBrightness = when {
            off -> 0.01f
            engine.brightness in 0..100 -> maxOf(engine.brightness / 100f, 0.02f)
            else -> WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        }
        window.attributes = lp
    }

    // ---------------------------------------------------------------- slide rendering

    private fun buildSlideView(s: Slide): View {
        val root = FrameLayout(this)
        root.setBackgroundColor(Color.BLACK)
        val sw = stage.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
        val sh = stage.height.takeIf { it > 0 } ?: resources.displayMetrics.heightPixels
        if (s.bitmaps.size >= 2) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            s.bitmaps.forEachIndexed { i, bm ->
                val cell = FrameLayout(this)
                cell.addView(ImageView(this).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    setImageBitmap(bm)
                }, FrameLayout.LayoutParams(-1, -1))
                if (app.prefs.showInfo) caption(s.assets[i])?.let { text ->
                    cell.addView(TextView(this).apply {
                        this.text = text
                        setTextColor(Color.parseColor("#F4F1EA")); textSize = 14f
                        setBackgroundResource(R.drawable.bg_pill)
                        setPadding(dp(12), dp(6), dp(12), dp(6))
                    }, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or if (i == 0) Gravity.START else Gravity.END).apply {
                        setMargins(dp(24), dp(24), dp(24), dp(24))
                    })
                }
                row.addView(cell, LinearLayout.LayoutParams(0, -1, 1f).apply { if (i > 0) marginStart = dp(6) })
            }
            root.addView(row, FrameLayout.LayoutParams(-1, -1))
            return root
        }
        val bm = s.bitmaps[0]
        val screenAr = sw.toFloat() / sh
        val ar = bm.width.toFloat() / bm.height
        if (abs(ar / screenAr - 1f) < 0.2f) {
            root.addView(ImageView(this).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP; setImageBitmap(bm)
            }, FrameLayout.LayoutParams(-1, -1))
        } else {
            // blurred fill behind a letterboxed photo (cheap blur: tiny bitmap scaled up)
            root.addView(ImageView(this).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                setImageBitmap(tinyBlur(bm))
                alpha = 0.55f
            }, FrameLayout.LayoutParams(-1, -1))
            root.addView(View(this).apply { setBackgroundColor(Color.parseColor("#590B0B0C")) }, FrameLayout.LayoutParams(-1, -1))
            root.addView(ImageView(this).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER; setImageBitmap(bm)
            }, FrameLayout.LayoutParams(-1, -1))
        }
        return root
    }

    private fun tinyBlur(bm: Bitmap): Bitmap {
        val w = 24
        val h = maxOf(1, w * bm.height / bm.width)
        val small = Bitmap.createScaledBitmap(bm, w * 4, h * 4, true)
        val tiny = Bitmap.createScaledBitmap(small, w, h, true)
        if (small !== bm) small.recycle()
        return tiny
    }

    private val inFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private val outFmt = SimpleDateFormat("d MMM yyyy", Locale.getDefault())

    private fun prettyDate(d: String?): String? = try {
        if (d == null || d.length < 10) null else outFmt.format(inFmt.parse(d.substring(0, 10))!!)
    } catch (e: Exception) { null }

    private fun caption(a: Asset): String? =
        listOfNotNull(a.place.ifBlank { null }, prettyDate(a.date)).joinToString(" · ").ifBlank { null }

    private fun updateInfo(s: Slide) {
        val a = s.assets.firstOrNull()
        val single = s.assets.size == 1
        infoBox.visibility = if (app.prefs.showInfo && single && a != null) View.VISIBLE else View.GONE
        albumChip.visibility = if (app.prefs.showAlbum) View.VISIBLE else View.GONE
        if (a == null) return
        place.text = a.place
        place.visibility = if (a.place.isBlank()) View.GONE else View.VISIBLE
        val m = listOfNotNull(prettyDate(a.date), a.people.take(4).joinToString(", ").ifBlank { null }).joinToString(" · ")
        meta.text = m
        meta.visibility = if (m.isBlank()) View.GONE else View.VISIBLE
        likedTag.visibility = if (a.favorite) View.VISIBLE else View.GONE
    }

    private fun startProgress() {
        progressAnim?.cancel()
        progressFill.scaleX = 0f
        val anim = ObjectAnimator.ofFloat(progressFill, "scaleX", 0f, 1f)
        anim.duration = app.prefs.interval * 1000L
        anim.interpolator = LinearInterpolator()
        anim.start()
        if (engine.paused) anim.pause()
        progressAnim = anim
    }

    // ---------------------------------------------------------------- controls + picker

    private fun setControls(show: Boolean) {
        ui.removeCallbacks(hideControls)
        if (show && picker.visibility == View.VISIBLE) return
        controls.visibility = if (show) View.VISIBLE else View.GONE
        if (show) ui.postDelayed(hideControls, 10_000)
    }

    private fun poke() {
        ui.removeCallbacks(hideControls)
        ui.postDelayed(hideControls, 10_000)
    }

    private fun openSetup() {
        startActivity(Intent(this, SetupActivity::class.java))
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    private class PickRow(val title: String, val sub: String, val onPick: () -> Unit)

    private fun openPicker() {
        ui.removeCallbacks(hideControls)
        controls.visibility = View.GONE
        picker.visibility = View.VISIBLE
        buildTabs()
        loadTab(tab)
    }

    private fun closePicker() {
        picker.visibility = View.GONE
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(askField.windowToken, 0)
        immersive()
    }

    private fun buildTabs() {
        tabs.removeAllViews()
        for (name in listOf("Albums", "People", "Memories", "Favorites", "All")) {
            val on = name == tab
            tabs.addView(Button(this, null, 0, android.R.style.Widget_Material_Button_Borderless).apply {
                text = name
                isAllCaps = false
                textSize = 15f
                stateListAnimator = null
                minWidth = 0; minimumWidth = 0
                setPadding(dp(16), 0, dp(16), 0)
                setTextColor(Color.parseColor(if (on) "#0B0B0C" else "#F4F1EA"))
                setBackgroundResource(if (on) R.drawable.bg_button else R.drawable.bg_button_ghost)
                setOnClickListener { tab = name; buildTabs(); loadTab(name) }
            }, LinearLayout.LayoutParams(-2, dp(44)).apply { marginEnd = dp(8) })
        }
    }

    private fun loadTab(name: String) {
        when (name) {
            "Memories" -> setRows(listOf(PickRow("On this day", "Photos from this date in earlier years") {
                choose(Source("memories", null, "On this day"))
            }))
            "Favorites" -> setRows(listOf(PickRow("Favorites", "Everything you liked") {
                choose(Source("favorites", null, "Favorites"))
            }))
            "All" -> setRows(listOf(PickRow("All photos", "Smart shuffle over your whole library") {
                choose(Source("all", null, "All photos"))
            }))
            else -> {
                setRows(listOf(PickRow("Loading…", "") {}))
                engine.scope.launch {
                    try {
                        val arr: JSONArray = if (name == "Albums") engine.albums() else engine.people()
                        if (tab != name) return@launch
                        val out = (0 until arr.length()).map { arr.getJSONObject(it) }.map { o ->
                            val type = if (name == "Albums") "album" else "person"
                            val sub = if (name == "Albums") "${o.optInt("count")} photos" else "Person"
                            PickRow(o.optString("name"), sub) {
                                choose(Source(type, o.optString("id"), o.optString("name")))
                            }
                        }
                        setRows(out.ifEmpty { listOf(PickRow("Nothing here yet", "") {}) })
                    } catch (e: Exception) {
                        setRows(listOf(PickRow("Couldn't load", e.message ?: "") {}))
                    }
                }
            }
        }
    }

    private fun choose(row: PickRow) = row.onPick()

    private fun choose(src: Source) {
        closePicker()
        engine.scope.launch { engine.play(src) }
    }

    private fun ask() {
        val q = askField.text.toString().trim()
        if (q.isEmpty()) return
        closePicker()
        engine.scope.launch {
            val n = engine.ask(q, null)
            if (n == 0) toast("Nothing found for “$q”")
        }
    }

    private fun setRows(r: List<PickRow>) {
        rows = r
        list.adapter = object : BaseAdapter() {
            override fun getCount() = rows.size
            override fun getItem(p: Int) = rows[p]
            override fun getItemId(p: Int) = p.toLong()
            override fun getView(p: Int, convert: View?, parent: ViewGroup): View {
                val row = rows[p]
                val box = (convert as? LinearLayout) ?: LinearLayout(this@SlideshowActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(16), dp(12), dp(16), dp(12))
                    minimumHeight = dp(64)
                    addView(TextView(context).apply { setTextColor(Color.parseColor("#F4F1EA")); textSize = 17f })
                    addView(TextView(context).apply { setTextColor(Color.parseColor("#A8A49C")); textSize = 14f })
                }
                val selected = row.title == engine.source.name
                box.setBackgroundColor(if (selected) Color.parseColor("#14F4F1EA") else Color.TRANSPARENT)
                (box.getChildAt(0) as TextView).text = row.title
                (box.getChildAt(1) as TextView).apply { text = row.sub; visibility = if (row.sub.isEmpty()) View.GONE else View.VISIBLE }
                return box
            }
        }
    }
}
