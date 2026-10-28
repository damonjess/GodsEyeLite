package com.gev.lite

import org.json.JSONArray
import org.json.JSONObject

/**
 * One public open-data camera catalog.
 *
 * Every pack here is keyless. The Android client fetches these directly, which
 * is the whole reason this app needs no server: a native HTTP client is not
 * subject to CORS, unlike a browser.
 */
internal class CctvPack(
    val key: String,
    val label: String,
    /** Attribution text required by (or courteous to) the data owner. */
    val credit: String,
    val url: String,
    val cap: Int,
    val parse: (String) -> List<RawCamera>,
)

internal class RawCamera(
    val id: String,
    val name: String,
    val lat: Double,
    val lon: Double,
    val imageUrl: String,
    /** Present for TfL jamcams, which publish a short looping clip. */
    val videoUrl: String? = null,
)

/** A camera as sent to the globe. */
data class CctvCamera(
    val id: String,
    val pack: String,
    val name: String,
    val lat: Double,
    val lon: Double,
    val imageUrl: String,
    val credit: String,
    /** TfL jamcams publish a few seconds of video alongside the still. */
    val videoUrl: String? = null,
)

/**
 * Fetches and normalizes the bundled camera packs.
 *
 * Shapes were verified against the live endpoints rather than assumed, because
 * these catalogs differ in ways that quietly break a parser: Austin publishes
 * the status of every camera (including ones that are not yet switched on),
 * the Socrata feeds (Austin, Calgary) return GeoJSON columns as nested
 * *objects* while other feeds embed the same payload as a JSON *string*, and
 * WSDOT publishes coordinates in Web Mercator metres rather than degrees.
 */
class CctvRepository(private val userAgent: String = "gods-eye-lite/0.1 (Android)") {

    companion object {
        private const val TIMEOUT_MS = 20_000

        /** WGS84 Web Mercator (EPSG:3857) half-circumference in metres. */
        private const val MERCATOR_ORIGIN_SHIFT = 20_037_508.34

        internal val PACKS: List<CctvPack> = listOf(
            CctvPack(
                key = "austin",
                label = "Austin",
                credit = "City of Austin Open Data (data.austintexas.gov)",
                // Only TURNED_ON cameras have a live image; the feed also carries
                // DESIRED, VOID and REMOVED entries.
                url = "https://data.austintexas.gov/resource/b4k4-adkb.json" +
                    "?%24where=camera_status%3D%27TURNED_ON%27&%24limit=1000&%24order=camera_id",
                cap = 900,
                parse = ::parseAustin,
            ),
            CctvPack(
                key = "calgary",
                label = "Calgary",
                credit = "Contains information licensed under the Open Government Licence - City of Calgary",
                url = "https://data.calgary.ca/resource/k7p9-kppz.json?%24limit=1000",
                cap = 400,
                parse = ::parseCalgary,
            ),
            CctvPack(
                key = "wsdot",
                label = "Washington",
                credit = "Washington State Department of Transportation (Travel Center)",
                url = "https://data.wsdot.wa.gov/travelcenter/Cameras.json",
                // Caps sit just above each feed's real size so nothing is dropped
                // arbitrarily: point primitives are cheap, and the whole set is
                // only a few thousand pins.
                cap = 1800,
                parse = ::parseWsdot,
            ),
            CctvPack(
                key = "tfl",
                label = "London",
                credit = "Powered by TfL Open Data. Contains OS data (c) Crown copyright and database rights",
                url = "https://api.tfl.gov.uk/Place/Type/JamCam",
                cap = 900,
                parse = ::parseTfl,
            ),
        )

        // ── Parsers ────────────────────────────────────────────────────────────

        private fun parseAustin(body: String): List<RawCamera> {
            val rows = JSONArray(body)
            val out = ArrayList<RawCamera>(rows.length())
            for (i in 0 until rows.length()) {
                val row = rows.optJSONObject(i) ?: continue
                val id = row.optString("camera_id").nonBlank() ?: continue
                val image = row.optString("screenshot_address").nonBlank() ?: continue
                val point = pointFrom(row, "location") ?: continue
                val name = row.optString("location_name").trim().ifBlank { "Camera $id" }
                out += RawCamera("austin:$id", name, point[1], point[0], image)
            }
            return out
        }

        private fun parseCalgary(body: String): List<RawCamera> {
            val rows = JSONArray(body)
            val out = ArrayList<RawCamera>(rows.length())
            for (i in 0 until rows.length()) {
                val row = rows.optJSONObject(i) ?: continue
                val image = row.nestedObject("camera_url")?.optString("url").nonBlank() ?: continue
                val point = pointFrom(row, "point") ?: continue
                val name = row.optString("camera_location").trim().ifBlank { "Camera ${i + 1}" }
                val id = image.substringAfterLast('/').substringBefore('?').ifBlank { "calgary-${i + 1}" }
                out += RawCamera("calgary:$id", name, point[1], point[0], image)
            }
            return out
        }

        private fun parseWsdot(body: String): List<RawCamera> {
            val features = JSONObject(body).optJSONArray("features") ?: return emptyList()
            val out = ArrayList<RawCamera>(features.length())
            for (i in 0 until features.length()) {
                val feature = features.optJSONObject(i) ?: continue
                val attrs = feature.optJSONObject("attributes") ?: continue
                val id = attrs.optString("CameraID").nonBlank() ?: continue
                val image = attrs.optString("ImageURL").nonBlank() ?: continue
                val geometry = feature.optJSONObject("geometry") ?: continue
                if (!geometry.has("x") || !geometry.has("y")) continue
                val (lat, lon) = mercatorToLatLon(geometry.optDouble("x"), geometry.optDouble("y"))
                val title = attrs.optString("CameraTitle").ifBlank { "Camera $id" }
                val direction = attrs.optString("CompassDirection").trim()
                val name = if (direction.isNotEmpty()) "$title ($direction)" else title
                out += RawCamera("wsdot:$id", name, lat, lon, image)
            }
            return out
        }

        private fun parseTfl(body: String): List<RawCamera> {
            val rows = JSONArray(body)
            val out = ArrayList<RawCamera>(rows.length())
            for (i in 0 until rows.length()) {
                val row = rows.optJSONObject(i) ?: continue
                val id = row.optString("id").nonBlank() ?: continue
                val lat = row.optDouble("lat", Double.NaN)
                val lon = row.optDouble("lon", Double.NaN)
                if (lat.isNaN() || lon.isNaN()) continue
                val properties = row.optJSONArray("additionalProperties")
                val image = propertyValue(properties, "imageUrl") ?: continue
                val video = propertyValue(properties, "videoUrl")
                val name = row.optString("commonName").trim().ifBlank { "JamCam $id" }
                out += RawCamera("tfl:$id", name, lat, lon, image, video)
            }
            return out
        }

        /** Reads a named entry from TfL's `additionalProperties` array. */
        private fun propertyValue(properties: JSONArray?, key: String): String? {
            if (properties == null) return null
            for (i in 0 until properties.length()) {
                val entry = properties.optJSONObject(i) ?: continue
                if (entry.optString("key") == key) {
                    return entry.optString("value").ifBlank { null }
                }
            }
            return null
        }

        /**
         * Reads a nested object that may be a real JSON object (as the Socrata
         * feeds return GeoJSON columns) or a JSON string (as other feeds embed
         * it). Assuming a single form is silent data loss: the wrong assumption
         * yields an empty pack rather than an error.
         */
        private fun JSONObject.nestedObject(key: String): JSONObject? {
            return when (val value = opt(key)) {
                is JSONObject -> value
                is String -> if (value.isBlank()) null else runCatching { JSONObject(value) }.getOrNull()
                else -> null
            }
        }

        /** `[lon, lat]` from a GeoJSON Point, whichever shape it arrives in. */
        private fun pointFrom(row: JSONObject, key: String): DoubleArray? {
            val coordinates = row.nestedObject(key)?.optJSONArray("coordinates") ?: return null
            if (coordinates.length() < 2) return null
            val lon = coordinates.optDouble(0, Double.NaN)
            val lat = coordinates.optDouble(1, Double.NaN)
            return if (lon.isFinite() && lat.isFinite()) doubleArrayOf(lon, lat) else null
        }

        /**
         * Blank-to-null. A plain `ifBlank { continue }` cannot be used here:
         * `continue` inside that inline lambda is an experimental Kotlin
         * feature this project does not enable.
         */
        private fun String?.nonBlank(): String? = if (this == null || isBlank()) null else this

        /** WSDOT geometry is EPSG:3857 metres, not degrees. */
        private fun mercatorToLatLon(x: Double, y: Double): Pair<Double, Double> {
            val lon = x / MERCATOR_ORIGIN_SHIFT * 180.0
            val lat = Math.toDegrees(
                2.0 * Math.atan(Math.exp(y / MERCATOR_ORIGIN_SHIFT * Math.PI)) - Math.PI / 2.0,
            )
            return lat to lon
        }
    }

    private fun httpGet(url: String): String? {
        val connection = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        return try {
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("User-Agent", userAgent)
            connection.setRequestProperty("Accept", "application/json")
            if (connection.responseCode !in 200..299) null
            else connection.inputStream.bufferedReader().use { it.readText() }
        } catch (error: Exception) {
            android.util.Log.w("CctvRepository", "GET failed for $url: ${error.message}")
            null
        } finally {
            connection.disconnect()
        }
    }

    /** Fetches every pack. A failing pack is skipped rather than failing the load. */
    fun loadAll(): List<CctvCamera> {
        val cameras = ArrayList<CctvCamera>(4000)
        for (pack in PACKS) {
            val body = httpGet(pack.url)
            if (body == null) {
                android.util.Log.w("CctvRepository", "pack ${pack.key} unavailable")
                continue
            }
            val parsed = runCatching { pack.parse(body) }
                .onFailure { android.util.Log.w("CctvRepository", "pack ${pack.key} parse failed: ${it.message}") }
                .getOrDefault(emptyList())
            for (raw in parsed.take(pack.cap)) {
                cameras += CctvCamera(
                    id = raw.id,
                    pack = pack.key,
                    name = raw.name,
                    lat = raw.lat,
                    lon = raw.lon,
                    imageUrl = raw.imageUrl,
                    credit = pack.credit,
                    videoUrl = raw.videoUrl,
                )
            }
        }
        return cameras
    }

    /** Fetches a single camera frame. */
    fun fetchFrame(url: String): ByteArray? {
        val connection = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        return try {
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("User-Agent", userAgent)
            connection.setRequestProperty("Cache-Control", "no-store")
            if (connection.responseCode !in 200..299) null
            else connection.inputStream.use { it.readBytes() }
        } catch (error: Exception) {
            android.util.Log.w("CctvRepository", "frame failed for $url: ${error.message}")
            null
        } finally {
            connection.disconnect()
        }
    }
}
