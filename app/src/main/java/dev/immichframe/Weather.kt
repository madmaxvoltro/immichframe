package dev.immichframe

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

/** Open-Meteo, no API key. Plain http on purpose: Android 6 lacks newer root certificates. */
object Weather {
    private val http = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).build()

    fun fetch(lat: String, lon: String): Pair<Int, String>? {
        if (lat.isBlank() || lon.isBlank()) return null
        val url = "http://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
            "&current=temperature_2m,weather_code"
        http.newCall(Request.Builder().url(url).build()).execute().use { r ->
            if (!r.isSuccessful) return null
            val cur = JSONObject(r.body?.string() ?: return null).optJSONObject("current") ?: return null
            return cur.optDouble("temperature_2m").roundToInt() to describe(cur.optInt("weather_code"))
        }
    }

    fun describe(code: Int): String = when (code) {
        0 -> "Clear"
        1 -> "Mostly clear"
        2 -> "Partly cloudy"
        3 -> "Cloudy"
        45, 48 -> "Fog"
        in 51..57 -> "Drizzle"
        in 61..67 -> "Rain"
        in 71..77 -> "Snow"
        in 80..82 -> "Showers"
        in 85..86 -> "Snow showers"
        in 95..99 -> "Thunderstorm"
        else -> ""
    }
}
