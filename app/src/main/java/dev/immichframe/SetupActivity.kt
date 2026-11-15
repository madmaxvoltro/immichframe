package dev.immichframe

import android.app.Activity
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SetupActivity : Activity() {
    private val app get() = App.instance
    private val prefs get() = app.prefs

    private lateinit var server: EditText
    private lateinit var apiKey: EditText
    private lateinit var name: EditText
    private lateinit var interval: EditText
    private lateinit var lat: EditText
    private lateinit var lon: EditText
    private lateinit var nightStart: EditText
    private lateinit var nightEnd: EditText
    private lateinit var testResult: TextView

    private fun sw(id: Int) = findViewById<Switch>(id)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_setup)
        server = findViewById(R.id.server)
        apiKey = findViewById(R.id.apiKey)
        name = findViewById(R.id.name)
        interval = findViewById(R.id.interval)
        lat = findViewById(R.id.lat)
        lon = findViewById(R.id.lon)
        nightStart = findViewById(R.id.nightStart)
        nightEnd = findViewById(R.id.nightEnd)
        testResult = findViewById(R.id.testResult)

        server.setText(prefs.server)
        apiKey.setText(prefs.apiKey)
        name.setText(prefs.frameName)
        interval.setText(prefs.interval.toString())
        lat.setText(prefs.lat)
        lon.setText(prefs.lon)
        nightStart.setText(prefs.nightStart)
        nightEnd.setText(prefs.nightEnd)
        sw(R.id.smart).isChecked = prefs.smart
        sw(R.id.pairing).isChecked = prefs.pairing
        sw(R.id.ovClock).isChecked = prefs.showClock
        sw(R.id.ovWeather).isChecked = prefs.showWeather
        sw(R.id.ovInfo).isChecked = prefs.showInfo
        sw(R.id.ovAlbum).isChecked = prefs.showAlbum
        sw(R.id.night).isChecked = prefs.nightEnabled

        findViewById<View>(R.id.btnSave).setOnClickListener { save(); finish() }
        findViewById<View>(R.id.btnTest).setOnClickListener { test() }
        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<View>(R.id.btnWifi).setOnClickListener { open(Settings.ACTION_WIFI_SETTINGS) }
        findViewById<View>(R.id.btnAndroid).setOnClickListener { open(Settings.ACTION_SETTINGS) }

        layoutFor(resources.configuration)
        showRemote()
    }

    override fun onResume() {
        super.onResume()
        // Phone finished setup via /api/config → close this screen and show photos.
        app.onConfigured = { if (prefs.configured) finish() }
    }

    override fun onPause() {
        super.onPause()
        app.onConfigured = null
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        layoutFor(newConfig)
    }

    private fun layoutFor(c: Configuration) {
        val portrait = c.orientation == Configuration.ORIENTATION_PORTRAIT
        val cols = findViewById<LinearLayout>(R.id.columns)
        cols.orientation = if (portrait) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
        val card = findViewById<View>(R.id.remoteCard)
        // portrait: QR card on top so it's visible without scrolling
        cols.removeView(card)
        if (portrait) cols.addView(card, 0) else cols.addView(card)
        (card.layoutParams as LinearLayout.LayoutParams).apply {
            width = if (portrait) LinearLayout.LayoutParams.MATCH_PARENT else (360 * resources.displayMetrics.density).toInt()
            bottomMargin = if (portrait) (32 * resources.displayMetrics.density).toInt() else 0
        }
        (card.layoutParams as LinearLayout.LayoutParams).apply {
            marginStart = if (portrait) 0 else (40 * resources.displayMetrics.density).toInt()
            topMargin = 0
        }
        val form = findViewById<View>(R.id.formCol)
        (form.layoutParams as LinearLayout.LayoutParams).apply {
            width = if (portrait) LinearLayout.LayoutParams.MATCH_PARENT else 0
            weight = if (portrait) 0f else 1f
        }
    }

    private fun open(action: String) {
        try { startActivity(Intent(action)) } catch (e: Exception) { testResult.text = "Can't open: ${e.message}" }
    }

    private fun save() {
        val changedServer = server.text.toString().trim().trimEnd('/') != prefs.server || apiKey.text.toString().trim() != prefs.apiKey
        prefs.server = server.text.toString()
        prefs.apiKey = apiKey.text.toString()
        prefs.frameName = name.text.toString().ifBlank { "Photo frame" }
        prefs.interval = interval.text.toString().toIntOrNull() ?: 30
        prefs.lat = lat.text.toString()
        prefs.lon = lon.text.toString()
        prefs.nightStart = nightStart.text.toString().ifBlank { "23:00" }
        prefs.nightEnd = nightEnd.text.toString().ifBlank { "07:00" }
        prefs.smart = sw(R.id.smart).isChecked
        prefs.pairing = sw(R.id.pairing).isChecked
        prefs.showClock = sw(R.id.ovClock).isChecked
        prefs.showWeather = sw(R.id.ovWeather).isChecked
        prefs.showInfo = sw(R.id.ovInfo).isChecked
        prefs.showAlbum = sw(R.id.ovAlbum).isChecked
        prefs.nightEnabled = sw(R.id.night).isChecked
        if (changedServer) app.cache.clear()
        app.engine.reload()
    }

    private fun test() {
        save()
        testResult.setTextColor(Color.parseColor("#A8A49C"))
        testResult.text = "Testing…"
        app.engine.scope.launch {
            try {
                val who = withContext(Dispatchers.IO) { app.immich.ping() }
                val albums = withContext(Dispatchers.IO) { app.immich.albums().length() }
                testResult.setTextColor(Color.parseColor("#8FD3C7"))
                testResult.text = "Connected as $who · $albums albums"
            } catch (e: Exception) {
                testResult.setTextColor(Color.parseColor("#FF9B8F"))
                testResult.text = "Failed: ${e.message}"
            }
        }
    }

    private fun showRemote() {
        val ip = App.localIp() ?: "<frame-ip>"
        val url = "http://$ip:${App.PORT}"
        findViewById<TextView>(R.id.apiUrl).text = "$url/setup"
        findViewById<TextView>(R.id.remoteUrl).text = "Remote control afterwards: $url"
        findViewById<ImageView>(R.id.qr).setImageBitmap(qr("$url/setup", 560))
    }

    private fun qr(text: String, size: Int): Bitmap {
        val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.MARGIN to 2))
        val px = IntArray(size * size) { i -> if (m.get(i % size, i / size)) Color.BLACK else Color.WHITE }
        return Bitmap.createBitmap(px, size, size, Bitmap.Config.RGB_565)
    }
}
