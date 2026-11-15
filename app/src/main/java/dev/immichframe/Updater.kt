package dev.immichframe

import android.app.PendingIntent
import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Self-update from GitHub Releases (BuildConfig.UPDATE_REPO, latest release, first .apk asset).
 * Device owner → installs silently. Otherwise Android shows an "Install" prompt on the frame.
 */
class Updater(private val app: App) {
    private val tag = "Updater"
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(120, TimeUnit.SECONDS).build()

    @Volatile var latestTag: String? = null; private set
    @Volatile var lastCheck = 0L; private set
    @Volatile var state = "idle"; private set      // idle | checking | downloading | installing | up-to-date | error
    @Volatile var error: String? = null; private set

    val isDeviceOwner: Boolean
        get() = (app.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager)
            .isDeviceOwnerApp(app.packageName)

    fun statusJson(): JSONObject = JSONObject()
        .put("current", BuildConfig.VERSION_NAME)
        .put("latest", latestTag)
        .put("available", latestTag?.let { code(it) > BuildConfig.VERSION_CODE } ?: false)
        .put("state", state)
        .put("error", error)
        .put("lastCheck", lastCheck)
        .put("silent", isDeviceOwner)
        .put("repo", BuildConfig.UPDATE_REPO)

    /** Blocking: check GitHub, and if newer (or force) download + install. Call off the main thread. */
    @Synchronized
    fun checkAndInstall(install: Boolean = true): JSONObject {
        try {
            state = "checking"; error = null
            val req = Request.Builder()
                .url("https://api.github.com/repos/${BuildConfig.UPDATE_REPO}/releases/latest")
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "immichframe-updater")
                .build()
            val rel = http.newCall(req).execute().use { r ->
                if (!r.isSuccessful) throw IOException("GitHub ${r.code}")
                JSONObject(r.body?.string() ?: "{}")
            }
            lastCheck = System.currentTimeMillis()
            val tagName = rel.optString("tag_name")
            latestTag = tagName
            if (code(tagName) <= BuildConfig.VERSION_CODE) { state = "up-to-date"; return statusJson() }
            if (!install) { state = "idle"; return statusJson() }

            val assets = rel.optJSONArray("assets")
            val url = (0 until (assets?.length() ?: 0)).map { assets!!.getJSONObject(it) }
                .firstOrNull { it.optString("name").endsWith(".apk") }
                ?.optString("browser_download_url") ?: throw IOException("release $tagName has no .apk")

            state = "downloading"
            val apk = File(app.cacheDir, "update.apk")
            http.newCall(Request.Builder().url(url).header("User-Agent", "immichframe-updater").build()).execute().use { r ->
                if (!r.isSuccessful) throw IOException("download ${r.code}")
                apk.outputStream().use { out -> r.body!!.byteStream().copyTo(out) }
            }
            state = "installing"
            install(apk)
        } catch (e: Exception) {
            Log.w(tag, "update failed", e)
            state = "error"; error = e.message ?: e.toString()
        }
        return statusJson()
    }

    private fun install(apk: File) {
        val pi = app.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(app.packageName)
        val id = pi.createSession(params)
        pi.openSession(id).use { s ->
            s.openWrite("base.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                s.fsync(out)
            }
            var flags = PendingIntent.FLAG_UPDATE_CURRENT
            if (Build.VERSION.SDK_INT >= 31) flags = flags or PendingIntent.FLAG_MUTABLE
            val pending = PendingIntent.getBroadcast(app, id, Intent(app, InstallResultReceiver::class.java), flags)
            s.commit(pending.intentSender)
        }
    }

    companion object {
        /** "v1.2.3" → 10203, same formula as app/build.gradle.kts */
        fun code(tagName: String): Int {
            val p = tagName.trim().removePrefix("v").split(".", "-").map { it.toIntOrNull() ?: 0 }
            return (p.getOrElse(0) { 0 }) * 10000 + (p.getOrElse(1) { 0 }) * 100 + (p.getOrElse(2) { 0 })
        }
    }
}

/** Result of the install session. Not device owner → launch Android's confirm screen. */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -999)
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                ctx.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            PackageInstaller.STATUS_SUCCESS -> Log.i("Updater", "installed")
            else -> Log.w("Updater", "install failed: $status ${intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)}")
        }
    }
}

/** After an update the process is replaced; bring the frame straight back. */
class UpdatedReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        ctx.startActivity(Intent(ctx, SlideshowActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

/** Lets the app be device owner (`dpm set-device-owner`) so updates install without a tap. */
class AdminReceiver : DeviceAdminReceiver()
