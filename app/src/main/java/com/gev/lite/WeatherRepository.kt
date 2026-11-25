package com.gev.lite

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/** Current conditions at one camera-city centre. */
data class CityWeather(
    val key: String,
    val city: String,
    val lat: Double,
    val lon: Double,
    val temperatureC: Double,
    val weatherCode: Int,
    val condition: String,
    val windKmh: Double,
)

private data class WeatherCity(
    val key: String,
    val label: String,
    val lat: Double,
    val lon: Double,
)

/**
 * Fetches current conditions for the cities represented by the camera packs.
 *
 * Open-Meteo is keyless and serves all twelve coordinates in one request. The
 * free endpoint is for non-commercial use, requires CC BY 4.0 attribution, and
 * allows 10,000 calls/day; the layer only refreshes once every 15 minutes while
 * it is on, so that budget is never a concern here.
 */
internal class WeatherRepository {

    companion object {
        private const val TAG = "WeatherRepository"
        private const val TIMEOUT_MS = 15_000
        private const val CACHE_TTL_MS = 10 * 60 * 1000L
        private const val API_URL = "https://api.open-meteo.com/v1/forecast"

        /**
         * One representative city per camera pack. These are the places named
         * in the camera chips, not every camera pixel; the weather layer is a
         * city-level overview and stays useful at globe scale.
         */
        private val CITIES = listOf(
            WeatherCity("austin", "Austin", 30.2672, -97.7431),
            WeatherCity("calgary", "Calgary", 51.0447, -114.0719),
            WeatherCity("wsdot", "Washington", 47.6062, -122.3321),
            WeatherCity("tfl", "London", 51.5072, -0.1276),
            WeatherCity("finland", "Finland", 60.1699, 24.9384),
            WeatherCity("norway", "Norway", 59.9139, 10.7522),
            WeatherCity("hongkong", "Hong Kong", 22.3193, 114.1694),
            WeatherCity("drivebc", "BC", 49.2827, -123.1207),
            WeatherCity("nsw", "NSW", -33.8688, 151.2093),
            WeatherCity("a22", "A22", 46.4983, 11.3548),
            WeatherCity("deldot", "Delaware", 39.1582, -75.5244),
            WeatherCity("california", "California", 38.5816, -121.4944),
        )
    }

    private val userAgent =
        "GodsEyeLite/1.0 (Android; weather layer; contact: dev@godseye-lite.app)"

    @Volatile
    private var cachedAtMs: Long = 0

    @Volatile
    private var cached: List<CityWeather>? = null

    /** Returns current readings, or an empty list when the API is unreachable. */
    fun load(): List<CityWeather> {
        val now = System.currentTimeMillis()
        cached?.let { if (now - cachedAtMs < CACHE_TTL_MS) return it }

        val latitudes = CITIES.joinToString(",") { String.format(Locale.US, "%.4f", it.lat) }
        val longitudes = CITIES.joinToString(",") { String.format(Locale.US, "%.4f", it.lon) }
        val url = "$API_URL?latitude=$latitudes&longitude=$longitudes" +
            "&current=temperature_2m,weather_code,wind_speed_10m" +
            "&temperature_unit=celsius&wind_speed_unit=kmh&timezone=auto"
        val body = httpGet(url) ?: return emptyList()
        val readings = runCatching { parse(body) }.getOrElse {
            android.util.Log.w(TAG, "could not parse weather: ${it.message}")
            emptyList()
        }
        if (readings.isNotEmpty()) {
            cached = readings
            cachedAtMs = now
        }
        return readings
    }

    internal fun parse(body: String): List<CityWeather> {
        val locations = runCatching { JSONArray(body) }.getOrElse {
            val single = runCatching { JSONObject(body) }.getOrNull() ?: return emptyList()
            JSONArray().put(single)
        }
        val out = ArrayList<CityWeather>()
        for (i in CITIES.indices) {
            val location = locations.optJSONObject(i) ?: continue
            val current = location.optJSONObject("current") ?: continue
            val temperature = current.optDouble("temperature_2m", Double.NaN)
            val code = current.optInt("weather_code", -1)
            if (!temperature.isFinite() || code < 0) continue
            val wind = current.optDouble("wind_speed_10m", Double.NaN)
            val city = CITIES[i]
            out.add(
                CityWeather(
                    key = city.key,
                    city = city.label,
                    lat = city.lat,
                    lon = city.lon,
                    temperatureC = temperature,
                    weatherCode = code,
                    condition = conditionFor(code),
                    windKmh = if (wind.isFinite()) wind else 0.0,
                ),
            )
        }
        return out
    }

    private fun conditionFor(code: Int): String = when (code) {
        0 -> "Clear"
        1 -> "Mainly clear"
        2 -> "Partly cloudy"
        3 -> "Overcast"
        45, 48 -> "Fog"
        51 -> "Light drizzle"
        53 -> "Drizzle"
        55 -> "Heavy drizzle"
        56, 57 -> "Freezing drizzle"
        61 -> "Light rain"
        63 -> "Rain"
        65 -> "Heavy rain"
        66, 67 -> "Freezing rain"
        71 -> "Light snow"
        73 -> "Snow"
        75 -> "Heavy snow"
        77 -> "Snow grains"
        80 -> "Light showers"
        81 -> "Showers"
        82 -> "Heavy showers"
        85 -> "Snow showers"
        86 -> "Heavy snow showers"
        95 -> "Thunderstorm"
        96, 99 -> "Thunderstorm with hail"
        else -> "Unknown"
    }

    fun toJson(readings: List<CityWeather>): JSONArray {
        val array = JSONArray()
        for (reading in readings) {
            array.put(
                JSONObject()
                    .put("k", reading.key)
                    .put("c", reading.city)
                    .put("la", reading.lat)
                    .put("lo", reading.lon)
                    .put("t", reading.temperatureC)
                    .put("code", reading.weatherCode)
                    .put("cond", reading.condition)
                    .put("w", reading.windKmh),
            )
        }
        return array
    }

    private fun httpGet(url: String): String? {
        val connection = URL(url).openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = "GET"
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("User-Agent", userAgent)
            connection.setRequestProperty("Accept", "application/json")
            if (connection.responseCode !in 200..299) {
                android.util.Log.w(TAG, "HTTP ${connection.responseCode} for $url")
                null
            } else {
                connection.inputStream.bufferedReader().use { it.readText() }
            }
        } catch (error: Exception) {
            android.util.Log.w(TAG, "GET failed for $url: ${error.message}")
            null
        } finally {
            connection.disconnect()
        }
    }
}
