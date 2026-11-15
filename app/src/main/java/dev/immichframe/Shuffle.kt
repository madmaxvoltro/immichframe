package dev.immichframe

import java.util.Calendar
import java.util.Random
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Smart shuffle: random, tilted toward what you like.
 *   w = 1
 *     × 3     liked (Immich favorite or liked on the frame)
 *     × 1.5   per liked photo of the same person (capped at 3×)
 *     × 2     "on this day" from an earlier year
 *     × 0.4   per "show less"
 *     × 0.8   per quick skip, recovering over 30 days
 *     × 0.05  if shown in the last 48 h
 *     = 0     hidden
 */
object Shuffle {
    private const val DAY = 86_400_000L

    fun weight(a: Asset, r: Stats.Row?, likedPeople: Map<String, Int>, now: Long, smart: Boolean): Double {
        if (r?.hidden == true) return 0.0
        val recent = r != null && now - r.lastShown < 2 * DAY
        if (!smart) return if (recent) 0.05 else 1.0

        var w = 1.0
        if (a.favorite || (r?.likes ?: 0) > 0) w *= 3.0
        var pb = 1.0
        for (p in a.people) repeat(likedPeople[p] ?: 0) { pb *= 1.5 }
        w *= min(pb, 3.0)
        if (onThisDay(a.date)) w *= 2.0
        if (r != null) {
            w *= 0.4.pow(r.less)
            if (r.skips > 0) {
                val days = (now - r.lastSkip).toDouble() / DAY
                w *= 0.8.pow(r.skips * max(0.0, 1.0 - days / 30.0))
            }
        }
        if (recent) w *= 0.05
        return w
    }

    fun onThisDay(date: String?): Boolean {
        if (date == null || date.length < 10) return false
        val c = Calendar.getInstance()
        val y = date.substring(0, 4).toIntOrNull() ?: return false
        val m = date.substring(5, 7).toIntOrNull() ?: return false
        val d = date.substring(8, 10).toIntOrNull() ?: return false
        return y < c.get(Calendar.YEAR) && m == c.get(Calendar.MONTH) + 1 && d == c.get(Calendar.DAY_OF_MONTH)
    }

    fun pick(pool: List<Asset>, w: DoubleArray, exclude: Set<String>, rnd: Random): Asset? {
        var total = 0.0
        for (i in pool.indices) if (pool[i].id !in exclude) total += w[i]
        if (total <= 0.0) return null
        var x = rnd.nextDouble() * total
        for (i in pool.indices) {
            if (pool[i].id in exclude) continue
            x -= w[i]
            if (x <= 0) return pool[i]
        }
        for (i in pool.indices.reversed()) if (pool[i].id !in exclude && w[i] > 0) return pool[i]
        return null
    }
}
