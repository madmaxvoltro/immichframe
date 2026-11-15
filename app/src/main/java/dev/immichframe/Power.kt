package dev.immichframe

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.os.PowerManager
import android.util.Log

/**
 * Real screen power. Device owner → lockNow() turns the backlight fully off and a wake lock turns it
 * back on. Otherwise the activity just shows black at minimum brightness (backlight stays faintly on).
 */
class Power(private val ctx: Context) {
    private val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
    private val dpm = ctx.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
    private var cpu: PowerManager.WakeLock? = null

    val canLock: Boolean get() = dpm.isDeviceOwnerApp(ctx.packageName)

    fun setScreen(on: Boolean) {
        if (!canLock) return
        try {
            if (on) {
                @Suppress("DEPRECATION")
                val wl = pm.newWakeLock(
                    PowerManager.FULL_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE,
                    "immichframe:wake")
                wl.acquire(5_000)
            } else if (pm.isInteractive) {
                dpm.lockNow()
            }
        } catch (e: Exception) {
            Log.w("Power", "screen ${if (on) "on" else "off"} failed", e)
        }
    }

    /** Keep the CPU running while the screen is off so the microphone keeps listening. */
    @Synchronized
    fun holdCpu(hold: Boolean) {
        if (hold && cpu == null) {
            cpu = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "immichframe:listen").apply { acquire() }
        } else if (!hold) {
            cpu?.let { if (it.isHeld) it.release() }
            cpu = null
        }
    }
}
