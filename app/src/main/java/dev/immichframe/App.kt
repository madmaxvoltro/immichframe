package dev.immichframe

import android.app.Application
import android.content.Intent
import android.util.Log
import fi.iki.elonen.NanoHTTPD
import java.net.Inet4Address
import java.net.NetworkInterface

class App : Application() {
    lateinit var prefs: Prefs
    lateinit var immich: Immich
    lateinit var stats: Stats
    lateinit var cache: PhotoCache
    lateinit var engine: Engine
    lateinit var updater: Updater
    private var server: ApiServer? = null

    /** Set by SetupActivity so it can close itself when the phone finishes setup. Main thread. */
    var onConfigured: (() -> Unit)? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        prefs = Prefs(this)
        immich = Immich(prefs)
        stats = Stats(this)
        cache = PhotoCache(this, immich)
        engine = Engine(this)
        updater = Updater(this)
        startServer()
        startUpdateLoop()
    }

    /** Check GitHub for a new release 2 min after start, then every 6 hours. */
    private fun startUpdateLoop() {
        Thread({
            Thread.sleep(120_000)
            while (true) {
                updater.checkAndInstall(true)
                Thread.sleep(6 * 3_600_000L)
            }
        }, "updater").apply { isDaemon = true }.start()
    }

    private fun startServer() {
        try {
            server = ApiServer(this, PORT).also { it.start(NanoHTTPD.SOCKET_READ_TIMEOUT, true) }
            Log.i("App", "API listening on :$PORT")
        } catch (e: Exception) {
            Log.e("App", "API server failed to start", e)
        }
    }

    fun restartUi() {
        startActivity(Intent(this, SlideshowActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
    }

    companion object {
        const val PORT = 8080
        lateinit var instance: App

        fun localIp(): String? = try {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }
                .firstOrNull { it is Inet4Address && !it.isLoopbackAddress }?.hostAddress
        } catch (e: Exception) { null }
    }
}
