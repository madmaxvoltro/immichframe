package dev.immichframe

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

/**
 * "Someone is nearby" from the microphone's loudness. Only a running loudness number is computed
 * from each half-second of sound; no audio is stored, recorded or sent anywhere.
 *
 * A slow-moving noise floor tracks the room's normal background (fridge, fan). A sound that is
 * clearly louder than that floor for about a second counts as activity.
 */
class Presence(private val app: App, private val onActivity: () -> Unit) {
    private val tag = "Presence"

    @Volatile var available: Boolean? = null; private set   // null = not tried yet
    @Volatile var error: String? = null; private set
    @Volatile var level = -90.0; private set                // dBFS, last half second
    @Volatile var floor = -60.0; private set                // background noise estimate
    @Volatile var lastSound = 0L; private set
    @Volatile private var running = false
    private var thread: Thread? = null

    val hasPermission: Boolean
        get() = app.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    /** 1 (only loud noises) .. 10 (whispers). Margin above the floor that counts as "someone". */
    private fun marginDb(): Double = 22.0 - app.prefs.sensitivity * 1.6

    @Synchronized
    fun start() {
        if (running) return
        if (!hasPermission) { available = null; error = "microphone permission not granted"; return }
        running = true
        thread = Thread({ loop() }, "presence").apply { isDaemon = true; start() }
    }

    @Synchronized
    fun stop() {
        running = false
        thread?.interrupt()
        thread = null
    }

    private fun loop() {
        val rate = 8000
        val minBuf = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuf <= 0) { fail("no microphone (buffer $minBuf)"); return }
        val rec = try {
            AudioRecord(MediaRecorder.AudioSource.MIC, rate, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, max(minBuf, rate))
        } catch (e: Exception) { fail("no microphone: ${e.message}"); return }
        if (rec.state != AudioRecord.STATE_INITIALIZED) { rec.release(); fail("no microphone (not initialized)"); return }

        val buf = ShortArray(rate / 2)   // half a second
        var loudWindows = 0
        try {
            rec.startRecording()
            if (rec.recordingState != AudioRecord.RECORDSTATE_RECORDING) { fail("microphone busy or missing"); return }
            available = true; error = null
            Log.i(tag, "listening")
            while (running && !Thread.currentThread().isInterrupted) {
                var n = 0
                while (n < buf.size && running) {
                    val r = rec.read(buf, n, buf.size - n)
                    if (r <= 0) { fail("read error $r"); return }
                    n += r
                }
                var sum = 0.0
                for (i in 0 until n) { val v = buf[i] / 32768.0; sum += v * v }
                val rms = sqrt(sum / max(1, n))
                val db = if (rms > 0) 20 * log10(rms) else -90.0
                level = db

                if (db > floor + marginDb()) {
                    loudWindows++
                    if (loudWindows >= 2) { lastSound = System.currentTimeMillis(); onActivity() }
                    floor += (db - floor) * 0.002          // barely let loud events raise the floor
                } else {
                    loudWindows = 0
                    floor += (db - floor) * 0.05           // follow the quiet background
                }
            }
        } catch (e: Exception) {
            fail(e.message ?: e.toString())
        } finally {
            runCatching { rec.stop() }
            rec.release()
            running = false
        }
    }

    private fun fail(msg: String) {
        Log.w(tag, msg)
        available = false; error = msg; running = false
    }

    fun json() = org.json.JSONObject()
        .put("enabled", app.prefs.presence)
        .put("permission", hasPermission)
        .put("microphone", available ?: org.json.JSONObject.NULL)
        .put("listening", running)
        .put("error", error)
        .put("levelDb", Math.round(level * 10) / 10.0)
        .put("floorDb", Math.round(floor * 10) / 10.0)
        .put("triggerDb", Math.round((floor + marginDb()) * 10) / 10.0)
        .put("lastSound", lastSound)
}
