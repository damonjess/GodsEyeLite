package com.gev.lite

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/** One point event on the globe: a quake, wildfire, storm/flood or volcano. */
data class GlobalEvent(
    val id: String,
    val kind: String,
    val title: String,
    val lat: Double,
    val lon: Double,
    val timeMs: Long,
    val magnitude: Double,
    val depthKm: Double,
    val source: String,
)

/**
 * Fetches two keyless public feeds and turns them into one small event list:
 *  - USGS all_day.geojson: every earthquake in the last 24 hours.
 *  - NASA EONET: open wildfires, volcanoes, severe storms and floods.
 *
 * Kotlin owns the network for the same reason it does for cameras and flights:
 * the WebView gets one JSON array, with no CORS or proxy in the way.
 */
internal class EventsRepository {

    companion object {
        private const val TAG = "EventsRepository"
        private const val TIMEOUT_MS = 15_000
        private const val MAX_EVENTS = 700

        private const val USGS_URL =
            "https://earthquake.usgs.gov/earthquakes/feed/v1.0/summary/all_day.geojson"
        private const val EONET_URL =
            "https://eonet.gsfc.nasa.gov/api/v3/events?status=open&days=30"
    }

    private val userAgent =
        "GodsEyeLite/1.0 (Android; events layer; contact: dev@godseye-lite.app)"

    /**
     * Returns whatever could be fetched. A failed USGS request must not hide
     * the EONET events, and vice versa; only both failing produces an empty
     * list, which the bridge reports as OFFLINE.
     */
    fun load(): List<GlobalEvent> {
        val found = ArrayList<GlobalEvent>()
        httpGet(USGS_URL)?.let { parseUsgs(it, found) }
        httpGet(EONET_URL)?.let { parseEonet(it, found) }
        return found.distinctBy { it.id }.take(MAX_EVENTS)
    }

    internal fun parseUsgs(body: String, out: MutableList<GlobalEvent>) {
        val features = runCatching { JSONObject(body).optJSONArray("features") }.getOrNull()
            ?: return
        for (i in 0 until features.length()) {
            val feature = features.optJSONObject(i) ?: continue
            val properties = feature.optJSONObject("properties") ?: continue
            val geometry = feature.optJSONObject("geometry") ?: continue
            val coordinates = geometry.optJSONArray("coordinates") ?: continue
            if (coordinates.length() < 2) continue

            val lon = coordinates.optDouble(0, Double.NaN)
            val lat = coordinates.optDouble(1, Double.NaN)
            val magnitude = properties.optDouble("mag", Double.NaN)
            if (!lat.isFinite() || !lon.isFinite() || !magnitude.isFinite()) continue

            val depth = if (coordinates.length() > 2) {
                coordinates.optDouble(2, 0.0)
            } else {
                0.0
            }
            val title = properties.optString("title").ifBlank {
                String.format(Locale.US, "M %.1f earthquake", magnitude)
            }
            val rawId = feature.optString("id").ifBlank {
                "usgs:$lat:$lon:${properties.optLong("time", 0L)}"
            }
            out.add(
                GlobalEvent(
                    id = "usgs:$rawId",
                    kind = "quake",
                    title = title,
                    lat = lat,
                    lon = lon,
                    timeMs = properties.optLong("time", 0L),
                    magnitude = magnitude,
                    depthKm = depth,
                    source = "USGS",
                ),
            )
        }
    }

    internal fun parseEonet(body: String, out: MutableList<GlobalEvent>) {
        val events = runCatching { JSONObject(body).optJSONArray("events") }.getOrNull()
            ?: return
        for (i in 0 until events.length()) {
            val event = events.optJSONObject(i) ?: continue
            val categories = event.optJSONArray("categories") ?: continue
            val ids = buildSet {
                for (c in 0 until categories.length()) {
                    categories.optJSONObject(c)?.optString("id")?.let { if (it.isNotBlank()) add(it) }
                }
            }
            val kind = when {
                ids.contains("wildfires") -> "fire"
                ids.contains("volcanoes") -> "volcano"
                ids.contains("severeStorms") || ids.contains("floods") -> "storm"
                else -> continue
            }

            val geometry = event.optJSONArray("geometry") ?: continue
            var point: Pair<Double, Double>? = null
            var timeMs = 0L
            for (g in geometry.length() - 1 downTo 0) {
                val entry = geometry.optJSONObject(g) ?: continue
                val candidate = pointOf(entry)
                if (candidate != null) {
                    point = candidate
                    timeMs = parseIsoTime(entry.optString("date"))
                    break
                }
            }
            val (lat, lon) = point ?: continue
            val rawId = event.optString("id").ifBlank { "eonet:$lat:$lon:$timeMs" }
            out.add(
                GlobalEvent(
                    id = "eonet:$rawId",
                    kind = kind,
                    title = event.optString("title").ifBlank { kind.replaceFirstChar { it.uppercase() } },
                    lat = lat,
                    lon = lon,
                    timeMs = if (timeMs > 0) timeMs else System.currentTimeMillis(),
                    magnitude = 0.0,
                    depthKm = 0.0,
                    source = "NASA EONET",
                ),
            )
        }
    }

    /** Point, or the centroid of a polygon ring. */
    private fun pointOf(geometry: JSONObject): Pair<Double, Double>? {
        val coordinates = geometry.optJSONArray("coordinates") ?: return null
        return when (geometry.optString("type")) {
            "Point" -> {
                if (coordinates.length() < 2) return null
                val lon = coordinates.optDouble(0, Double.NaN)
                val lat = coordinates.optDouble(1, Double.NaN)
                if (!lat.isFinite() || !lon.isFinite()) null else lat to lon
            }
            "Polygon" -> coordinates.optJSONArray(0)?.let { ring -> centroid(ring) }
            "MultiPolygon" -> coordinates.optJSONArray(0)
                ?.optJSONArray(0)
                ?.let { ring -> centroid(ring) }
            else -> null
        }
    }

    /**
     * Longitude uses a circular mean so a ring crossing the antimeridian does
     * not land on the opposite side of the planet.
     */
    private fun centroid(ring: JSONArray): Pair<Double, Double>? {
        var x = 0.0
        var y = 0.0
        var latSum = 0.0
        var count = 0
        for (i in 0 until ring.length()) {
            val point = ring.optJSONArray(i) ?: continue
            if (point.length() < 2) continue
            val lon = point.optDouble(0, Double.NaN)
            val lat = point.optDouble(1, Double.NaN)
            if (!lon.isFinite() || !lat.isFinite()) continue
            val radians = Math.toRadians(lon)
            x += cos(radians)
            y += sin(radians)
            latSum += lat
            count++
        }
        if (count == 0) return null
        val meanLon = Math.toDegrees(atan2(y / count, x / count))
        return (latSum / count) to meanLon
    }

    private fun parseIsoTime(value: String): Long {
        if (value.isBlank()) return 0L
        for (pattern in listOf("yyyy-MM-dd'T'HH:mm:ss'Z'", "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")) {
            val parsed = runCatching {
                SimpleDateFormat(pattern, Locale.US).apply {
                    timeZone = TimeZone.getTimeZone("UTC")
                }.parse(value)
            }.getOrNull()
            if (parsed != null) return parsed.time
        }
        return 0L
    }

    fun toJson(events: List<GlobalEvent>): JSONArray {
        val array = JSONArray()
        for (event in events) {
            array.put(
                JSONObject()
                    .put("i", event.id)
                    .put("k", event.kind)
                    .put("t", event.title)
                    .put("la", event.lat)
                    .put("lo", event.lon)
                    .put("ts", event.timeMs)
                    .put("m", event.magnitude)
                    .put("d", event.depthKm)
                    .put("s", event.source),
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
