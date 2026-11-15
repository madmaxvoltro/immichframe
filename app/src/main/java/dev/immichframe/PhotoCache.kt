package dev.immichframe

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File

/** Disk cache of Immich "preview" thumbnails + memory-safe decoding (frame has ~512 MB RAM). */
class PhotoCache(ctx: Context, private val immich: Immich) {
    private val dir = File(ctx.cacheDir, "photos").apply { mkdirs() }
    private val maxBytes = 1_500L * 1024 * 1024
    private var writes = 0

    fun file(id: String) = File(dir, "$id.img")

    @Synchronized
    fun ensure(id: String): File {
        val f = file(id)
        if (!f.exists() || f.length() == 0L) {
            val tmp = File(dir, "$id.tmp")
            immich.downloadPreview(id, tmp)
            if (!tmp.renameTo(f)) { tmp.copyTo(f, true); tmp.delete() }
            if (++writes % 25 == 0) trim()
        } else {
            f.setLastModified(System.currentTimeMillis())
        }
        return f
    }

    fun bounds(f: File): Pair<Int, Int> {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, o)
        return o.outWidth to o.outHeight
    }

    /** Decode so the result is at least ~target size but never much larger. RGB_565 halves memory. */
    fun decode(f: File, targetW: Int, targetH: Int): Bitmap? {
        val (w, h) = bounds(f)
        if (w <= 0 || h <= 0) return null
        var sample = 1
        while (w / (sample * 2) >= targetW && h / (sample * 2) >= targetH) sample *= 2
        val o = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        return BitmapFactory.decodeFile(f.path, o)
    }

    private fun trim() {
        val files = dir.listFiles()?.filter { it.name.endsWith(".img") } ?: return
        var total = files.sumOf { it.length() }
        if (total <= maxBytes) return
        for (f in files.sortedBy { it.lastModified() }) {
            if (total <= maxBytes * 8 / 10) break
            total -= f.length()
            f.delete()
        }
    }

    fun clear() { dir.listFiles()?.forEach { it.delete() } }
}
