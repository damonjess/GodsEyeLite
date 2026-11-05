package com.gev.lite

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.Executors

/** One satellite with its current GP element set. The WebView does the SGP4 propagation. */
data class Satellite(
    val norad: String,
    val name: String,
    val line1: String,
    val line2: String,
    val category: String,
)

/**
 * Fetches a deliberately small, useful satellite catalog from CelesTrak.
 *
 * CelesTrak publishes keyless, public GP element sets. The WebView receives
 * the TLEs and propagates them locally with satellite.js, so the app does not
 * need to poll a tracking API for every satellite position.
 *
 * There is no CORS problem here because Kotlin owns the network; the WebView
 * only sees the resulting JSON over the bridge. The groups are fetched in
 * parallel and deduplicated by NORAD ID, keeping the first, more specific
 * category when a satellite appears in several groups.
 */
internal class SatelliteRepository {

    companion object {
        private const val TAG = "SatelliteRepository"
        private const val TIMEOUT_MS = 10_000
        private const val CACHE_TTL_MS = 6 * 60 * 60 * 1000L
        private const val MAX_SATELLITES = 800
        private const val CELESTRAK_URL =
            "https://celestrak.org/NORAD/elements/gp.php?GROUP=%s&FORMAT=tle"

        // CelesTrak tags are specific enough to drive the layer filters. The
        // GLONASS group is named "glo-ops", not "glonass-ops".
        private val GROUPS = listOf(
            "stations" to "station",
            "weather" to "weather",
            "gps-ops" to "nav",
            "glo-ops" to "nav",
            "galileo" to "nav",
            "beidou" to "nav",
            "military" to "military",
            "science" to "science",
        )
    }

    @Volatile
    private var cached: List<Satellite>? = null

    @Volatile
    private var cachedAtMs: Long = 0

    private val userAgent =
        "GodsEyeLite/1.0 (Android; satellite layer; contact: dev@godseye-lite.app)"

    /** Returns the current catalog, or an empty list if every upstream group failed. */
    fun load(): List<Satellite> {
        val now = System.currentTimeMillis()
        cached?.let { if (now - cachedAtMs < CACHE_TTL_MS) return it }

        val found = LinkedHashMap<String, Satellite>()
        val pool = Executors.newFixedThreadPool(4)
        try {
            val futures = GROUPS.map { (group, category) ->
                pool.submit<Pair<String, String?>> {
                    category to httpGet(String.format(Locale.US, CELESTRAK_URL, group))
                }
            }
            for (future in futures) {
                val result = runCatching { future.get() }.getOrNull() ?: continue
                val body = result.second ?: continue
                parseGroup(body, result.first, found)
            }
        } finally {
            pool.shutdownNow()
        }

        val list = found.values.take(MAX_SATELLITES)
        if (list.isNotEmpty()) {
            cached = list
            cachedAtMs = now
        }
        return list
    }

    /**
     * Parses CelesTrak's three-line TLE format. Empty and malformed entries are
     * skipped instead of failing the whole group.
     */
    internal fun parseGroup(body: String, category: String, out: MutableMap<String, Satellite>) {
        val lines = body.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        var i = 0
        while (i + 2 < lines.size) {
            val nameLine = lines[i]
            val line1 = lines[i + 1]
            val line2 = lines[i + 2]
            if (line1.startsWith("1 ") && line2.startsWith("2 ") && line1.length >= 7) {
                val norad = line1.substring(2, 7).trim()
                if (norad.isNotEmpty()) {
                    val name = nameLine.removePrefix("0 ").trim().ifBlank { norad }
                    out.putIfAbsent(norad, Satellite(norad, name, line1, line2, category))
                }
                i += 3
            } else {
                // A missing name line should not prevent the following pair
                // from being considered; move one line rather than dropping it.
                i++
            }
        }
    }

    fun toJson(satellites: List<Satellite>): JSONArray {
        val array = JSONArray()
        for (satellite in satellites) {
            array.put(
                JSONObject()
                    .put("id", satellite.norad)
                    .put("n", satellite.name)
                    .put("l1", satellite.line1)
                    .put("l2", satellite.line2)
                    .put("cat", satellite.category),
            )
        }
        return array
    }

    private fun httpGet(url: String): String? {
        val connection = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        return try {
            connection.requestMethod = "GET"
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("User-Agent", userAgent)
            connection.setRequestProperty("Accept", "text/plain")
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
