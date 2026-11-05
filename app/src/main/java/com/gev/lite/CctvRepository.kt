package com.gev.lite

import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.zip.GZIPInputStream

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
    /** Additional catalog documents merged into this pack (Caltrans districts). */
    val extraUrls: List<String> = emptyList(),
    /** Extra request headers a feed asks for (Digitraffic identifies clients). */
    val headers: Map<String, String> = emptyMap(),
)

internal class RawCamera(
    val id: String,
    val name: String,
    val lat: Double,
    val lon: Double,
    val imageUrl: String,
    /** Present for feeds that publish video (HLS or MP4). */
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
    /** Video URL for feeds that publish one (HLS or MP4). */
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

        /**
         * NSW's image host answers non-browser clients with an HTML placeholder
         * instead of the JPEG. Its own web map loads these stills with a browser
         * UA, so this one host is identified that way; every other feed gets the
         * app's own identifier.
         */
        private const val BROWSER_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/126 Mobile Safari/537.36"

        /** WGS84 Web Mercator (EPSG:3857) half-circumference in metres. */
        private const val MERCATOR_ORIGIN_SHIFT = 20_037_508.34

        /** Caltrans publishes one CCTV document per district, numbered D01-D12. */
        private val CALTRANS_URLS = (1..12).map { district ->
            val suffix = district.toString().padStart(2, '0')
            "https://cwwp2.dot.ca.gov/data/d$district/cctv/cctvStatusD$suffix.json"
        }

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
            CctvPack(
                key = "finland",
                label = "Finland",
                credit = "Fintraffic / digitraffic.fi, license CC BY 4.0",
                url = "https://tie.digitraffic.fi/api/weathercam/v1/stations",
                cap = 2300,
                parse = ::parseFinland,
                headers = mapOf("Digitraffic-User" to "gods-eye-lite"),
            ),
            CctvPack(
                key = "norway",
                label = "Norway",
                credit = "Contains data under the Norwegian licence for Open Government data (NLOD) distributed by Statens vegvesen",
                url = "https://ogckart-sn1.atlas.vegvesen.no/ogc/features/v1/collections/datex_3_1:CctvSimple/items?limit=2000",
                cap = 900,
                parse = ::parseNorway,
            ),
            CctvPack(
                key = "hongkong",
                label = "Hong Kong",
                credit = "Hong Kong Transport Department / DATA.GOV.HK",
                url = "https://static.data.gov.hk/td/traffic-snapshot-images/code/Traffic_Camera_Locations_En.xml",
                cap = 1100,
                parse = ::parseHongKong,
            ),
            CctvPack(
                key = "drivebc",
                label = "BC",
                credit = "DriveBC. Contains information licensed under the Open Government Licence - British Columbia",
                url = "https://www.drivebc.ca/api/webcams/",
                cap = 1100,
                parse = ::parseDriveBc,
            ),
            CctvPack(
                key = "nsw",
                label = "NSW",
                credit = "Live Traffic NSW - Transport for NSW (CC BY 4.0)",
                url = "https://data.livetraffic.com/cameras/traffic-cam.json",
                cap = 300,
                parse = ::parseNsw,
            ),
            CctvPack(
                key = "a22",
                label = "A22",
                credit = "Webcam images (c) Autostrada del Brennero S.p.A., via the Open Data Hub (NOI Techpark)",
                url = "https://tourism.api.opendatahub.com/v1/WebcamInfo?source=a22&pagesize=100",
                cap = 20,
                parse = ::parseA22,
            ),
            CctvPack(
                key = "deldot",
                label = "Delaware",
                credit = "DelDOT - Delaware Department of Transportation",
                url = "https://tmc.deldot.gov/json/videocamera.json",
                cap = 400,
                parse = ::parseDelDOT,
            ),
            CctvPack(
                key = "california",
                label = "California",
                credit = "California Department of Transportation (Caltrans) CCTV",
                url = CALTRANS_URLS.first(),
                cap = 2300,
                parse = ::parseCaltrans,
                extraUrls = CALTRANS_URLS.drop(1),
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

        private fun parseFinland(body: String): List<RawCamera> {
            val features = JSONObject(body).optJSONArray("features") ?: return emptyList()
            val out = ArrayList<RawCamera>(2300)
            for (i in 0 until features.length()) {
                val feature = features.optJSONObject(i) ?: continue
                val properties = feature.optJSONObject("properties") ?: continue
                // Stations that are not gathering would only serve stale frames.
                if (properties.optString("collectionStatus") != "GATHERING") continue
                val point = feature.optJSONObject("geometry")?.optJSONArray("coordinates") ?: continue
                if (point.length() < 2) continue
                val lon = point.optDouble(0, Double.NaN)
                val lat = point.optDouble(1, Double.NaN)
                if (!lat.isFinite() || !lon.isFinite()) continue
                val station = properties.optString("name").trim()
                val presets = properties.optJSONArray("presets") ?: continue
                for (j in 0 until presets.length()) {
                    val preset = presets.optJSONObject(j) ?: continue
                    if (!preset.optBoolean("inCollection", true)) continue
                    val presetId = preset.optString("id").nonBlank() ?: continue
                    // One station publishes a preset per view; the last two
                    // digits identify the view, so the cards stay distinct.
                    val name = if (station.isBlank()) {
                        "Camera $presetId"
                    } else {
                        "$station ${presetId.takeLast(2)}"
                    }
                    out += RawCamera(
                        "finland:$presetId",
                        name,
                        lat,
                        lon,
                        "https://weathercam.digitraffic.fi/$presetId.jpg",
                    )
                }
            }
            return out
        }

        private fun parseNorway(body: String): List<RawCamera> {
            val features = JSONObject(body).optJSONArray("features") ?: return emptyList()
            val out = ArrayList<RawCamera>(features.length())
            for (i in 0 until features.length()) {
                val feature = features.optJSONObject(i) ?: continue
                val properties = feature.optJSONObject("properties") ?: continue
                val image = properties.optString("stillImageUrl").nonBlank() ?: continue
                if (!image.startsWith("https://kamera.atlas.vegvesen.no/")) continue
                val point = feature.optJSONObject("geometry")?.optJSONArray("coordinates") ?: continue
                if (point.length() < 2) continue
                val lon = point.optDouble(0, Double.NaN)
                val lat = point.optDouble(1, Double.NaN)
                if (!lat.isFinite() || !lon.isFinite()) continue
                val id = properties.optString("cameraId").nonBlank()
                    ?: properties.optString("recordId").nonBlank()
                    ?: "norway-${i + 1}"
                val video = properties.optString("videoUrl").nonBlank()
                    ?.takeIf { it.startsWith("https://kamera.vegvesen.no/") }
                val place = properties.optString("description").trim()
                val facing = properties.optString("orientationDescription").trim()
                val name = when {
                    place.isNotBlank() && facing.isNotBlank() && place != facing -> "$place ($facing)"
                    place.isNotBlank() -> place
                    facing.isNotBlank() -> facing
                    else -> "Camera $id"
                }
                out += RawCamera("norway:$id", name, lat, lon, image, video)
            }
            return out
        }

        private fun parseHongKong(body: String): List<RawCamera> {
            val out = ArrayList<RawCamera>(1100)
            for (block in Regex("<image>([\\s\\S]*?)</image>").findAll(body)) {
                val xml = block.groupValues[1]
                val key = xmlTag(xml, "key") ?: continue
                val lat = xmlTag(xml, "latitude")?.toDoubleOrNull() ?: continue
                val lon = xmlTag(xml, "longitude")?.toDoubleOrNull() ?: continue
                val description = xmlTag(xml, "description").orEmpty()
                val image = xmlTag(xml, "url")
                    ?.takeIf { it.startsWith("https://tdcctv.data.one.gov.hk/") }
                    ?: "https://tdcctv.data.one.gov.hk/$key.JPG"
                out += RawCamera(
                    "hongkong:$key",
                    description.ifBlank { "Camera $key" },
                    lat,
                    lon,
                    image,
                )
            }
            return out
        }

        private fun parseDriveBc(body: String): List<RawCamera> {
            val rows = JSONArray(body)
            val out = ArrayList<RawCamera>(rows.length())
            for (i in 0 until rows.length()) {
                val row = rows.optJSONObject(i) ?: continue
                if (!row.optBoolean("is_on", true)) continue
                if (!row.optBoolean("should_appear", true)) continue
                val id = row.optString("id").nonBlank() ?: continue
                val path = row.optJSONObject("links")?.optString("imageDisplay").nonBlank() ?: continue
                if (!path.startsWith("/images/")) continue
                val point = row.optJSONObject("location")?.optJSONArray("coordinates") ?: continue
                if (point.length() < 2) continue
                val lon = point.optDouble(0, Double.NaN)
                val lat = point.optDouble(1, Double.NaN)
                if (!lat.isFinite() || !lon.isFinite()) continue
                val name = row.optString("name").trim()
                    .ifBlank { row.optString("caption").trim() }
                    .ifBlank { "Camera $id" }
                out += RawCamera("drivebc:$id", name, lat, lon, "https://www.drivebc.ca$path")
            }
            return out
        }

        private fun parseNsw(body: String): List<RawCamera> {
            val features = JSONObject(body).optJSONArray("features") ?: return emptyList()
            val out = ArrayList<RawCamera>(features.length())
            for (i in 0 until features.length()) {
                val feature = features.optJSONObject(i) ?: continue
                val properties = feature.optJSONObject("properties") ?: continue
                val image = properties.optString("href").nonBlank() ?: continue
                if (!image.startsWith("https://webcams.transport.nsw.gov.au/")) continue
                val point = feature.optJSONObject("geometry")?.optJSONArray("coordinates") ?: continue
                if (point.length() < 2) continue
                val lon = point.optDouble(0, Double.NaN)
                val lat = point.optDouble(1, Double.NaN)
                if (!lat.isFinite() || !lon.isFinite()) continue
                val id = feature.optString("id").nonBlank() ?: "nsw-${i + 1}"
                val name = properties.optString("title").trim().ifBlank { "Camera ${i + 1}" }
                out += RawCamera("nsw:$id", name, lat, lon, image)
            }
            return out
        }

        private fun parseA22(body: String): List<RawCamera> {
            val items = JSONObject(body).optJSONArray("Items") ?: return emptyList()
            val out = ArrayList<RawCamera>(items.length())
            for (i in 0 until items.length()) {
                val item = items.optJSONObject(i) ?: continue
                if (!item.optBoolean("Active", true)) continue
                val image = item.optString("Webcamurl").nonBlank() ?: continue
                if (!image.startsWith("https://www.autobrennero.it/WebCamImg/")) continue
                val point = item.optJSONObject("GpsPoints")?.optJSONObject("position")
                    ?: item.optJSONArray("GpsInfo")?.optJSONObject(0)
                    ?: continue
                val lat = point.optDouble("Latitude", Double.NaN)
                val lon = point.optDouble("Longitude", Double.NaN)
                if (!lat.isFinite() || !lon.isFinite()) continue
                val id = item.optString("Id").nonBlank() ?: "a22-${i + 1}"
                val name = item.optString("Shortname").trim().ifBlank { "A22 camera $id" }
                out += RawCamera("a22:$id", name, lat, lon, image)
            }
            return out
        }

        private fun parseDelDOT(body: String): List<RawCamera> {
            val rows = JSONObject(body).optJSONArray("videoCameras") ?: return emptyList()
            val out = ArrayList<RawCamera>(rows.length())
            for (i in 0 until rows.length()) {
                val row = rows.optJSONObject(i) ?: continue
                if (!row.optBoolean("enabled", true)) continue
                if (!row.optString("status").equals("Active", ignoreCase = true)) continue
                val id = row.optString("id").nonBlank() ?: continue
                val video = row.optJSONObject("urls")?.optString("m3u8s").nonBlank()
                    // The catalog writes the https URL with an explicit :443;
                    // the default port is the same endpoint.
                    ?.replace("https://video.deldot.gov:443/", "https://video.deldot.gov/")
                    ?: continue
                // The catalog carries plain-http and https playlists; only the
                // https one can play from this app's https origin.
                if (!video.startsWith("https://video.deldot.gov/")) continue
                val lat = row.optDouble("lat", Double.NaN)
                val lon = row.optDouble("lon", Double.NaN)
                if (!lat.isFinite() || !lon.isFinite()) continue
                val title = row.optString("title").trim().ifBlank { "Camera $id" }
                // DelDOT publishes live video only, with no still frame. The
                // panel plays the HLS stream; the empty image keeps the globe
                // card a labelled LIVE card rather than a broken picture.
                out += RawCamera("deldot:$id", title, lat, lon, "", video)
            }
            return out
        }

        private fun parseCaltrans(body: String): List<RawCamera> {
            val rows = JSONObject(body).optJSONArray("data") ?: return emptyList()
            val out = ArrayList<RawCamera>(rows.length())
            for (i in 0 until rows.length()) {
                val cctv = rows.optJSONObject(i)?.optJSONObject("cctv") ?: continue
                if (!cctv.optString("inService").equals("true", ignoreCase = true)) continue
                val location = cctv.optJSONObject("location") ?: continue
                val lat = location.optString("latitude").toDoubleOrNull() ?: continue
                val lon = location.optString("longitude").toDoubleOrNull() ?: continue
                if (!lat.isFinite() || !lon.isFinite()) continue
                val imageData = cctv.optJSONObject("imageData") ?: continue
                val video = imageData.optString("streamingVideoURL").nonBlank()
                    // Most rows use the bare host; a few spell the default
                    // port or plain http. Normalize those to the working HTTPS
                    // endpoint instead of dropping otherwise live cameras.
                    ?.replace("https://wzmedia.dot.ca.gov:443/", "https://wzmedia.dot.ca.gov/")
                    ?.replace("http://wzmedia.dot.ca.gov/", "https://wzmedia.dot.ca.gov/")
                    ?.takeIf { it.startsWith("https://wzmedia.dot.ca.gov/") }
                    ?: continue
                val still = imageData.optJSONObject("static")
                    ?.optString("currentImageURL")
                    ?.takeIf { it.startsWith("https://cwwp2.dot.ca.gov/") }
                    ?: ""
                val index = cctv.optString("index").ifBlank { "${i + 1}" }
                // District-local indexes repeat, so include the stream's D-number.
                val district = video.removePrefix("https://wzmedia.dot.ca.gov/")
                    .substringBefore('/')
                val id = if (district.isBlank()) "row-${i + 1}" else "$district:$index"
                val name = location.optString("locationName").trim()
                    .ifBlank { "Caltrans camera $id" }
                out += RawCamera("california:$id", name, lat, lon, still, video)
            }
            return out
        }

        /** Reads a simple text element from one XML fragment, decoding entities. */
        private fun xmlTag(xml: String, name: String): String? {
            val match = Regex("<$name>([^<]*)</$name>").find(xml) ?: return null
            return match.groupValues[1]
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .trim()
                .ifBlank { null }
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

    private fun httpGet(url: String, headers: Map<String, String> = emptyMap()): String? {
        val connection = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        return try {
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("User-Agent", userAgent)
            connection.setRequestProperty("Accept", "application/json")
            // Digitraffic refuses clients that do not accept gzip (HTTP 406),
            // and the large catalogs compress to a fraction of their size.
            connection.setRequestProperty("Accept-Encoding", "gzip")
            for ((name, value) in headers) connection.setRequestProperty(name, value)
            if (connection.responseCode !in 200..299) {
                null
            } else {
                val stream = if (connection.contentEncoding?.contains("gzip", ignoreCase = true) == true) {
                    GZIPInputStream(connection.inputStream)
                } else {
                    connection.inputStream
                }
                stream.bufferedReader().use { it.readText() }
            }
        } catch (error: Exception) {
            android.util.Log.w("CctvRepository", "GET failed for $url: ${error.message}")
            null
        } finally {
            connection.disconnect()
        }
    }

    /** NSW's image host needs a browser UA; every other feed gets the app's own. */
    private fun frameUserAgent(url: String): String =
        if (url.startsWith("https://webcams.transport.nsw.gov.au/")) {
            BROWSER_USER_AGENT
        } else {
            userAgent
        }

    /**
     * Fetches every pack in parallel. Catalogs fetched in sequence made
     * startup wait on each host in turn, so one slow feed delayed the rest;
     * the worker pool keeps a dead host to its own timeout. A failing pack is
     * skipped rather than failing the whole load.
     */
    fun loadAll(): List<CctvCamera> {
        val pool = Executors.newFixedThreadPool(minOf(6, PACKS.size))
        return try {
            val futures = PACKS.map { pack ->
                pool.submit<List<CctvCamera>> { loadPack(pack) }
            }
            futures.flatMap { future ->
                runCatching { future.get() }.getOrDefault(emptyList())
            }
        } finally {
            pool.shutdown()
        }
    }

    private fun loadPack(pack: CctvPack): List<CctvCamera> {
        val bodies = fetchPackBodies(pack)
        if (bodies.isEmpty()) {
            android.util.Log.w("CctvRepository", "pack ${pack.key} unavailable")
            return emptyList()
        }
        val parsed = bodies.flatMap { body ->
            runCatching { pack.parse(body) }
                .onFailure {
                    android.util.Log.w(
                        "CctvRepository",
                        "pack ${pack.key} parse failed: ${it.message}",
                    )
                }
                .getOrDefault(emptyList())
        }
        return parsed.take(pack.cap).map { raw ->
            CctvCamera(
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

    /**
     * Fetches a pack's primary document and any extra district documents.
     * Extra sources are fetched concurrently so California's twelve districts
     * do not turn startup into a serial download.
     */
    private fun fetchPackBodies(pack: CctvPack): List<String> {
        val urls = listOf(pack.url) + pack.extraUrls
        if (urls.size == 1) return listOfNotNull(httpGet(pack.url, pack.headers))
        val pool = Executors.newFixedThreadPool(minOf(4, urls.size))
        return try {
            val futures = urls.map { url -> pool.submit<String?> { httpGet(url, pack.headers) } }
            futures.mapNotNull { future -> runCatching { future.get() }.getOrNull() }
        } finally {
            pool.shutdown()
        }
    }

    /** Fetches a single camera frame. */
    fun fetchFrame(url: String): ByteArray? {
        // A live-only camera (DelDOT) has no still URL. URL("") throws before
        // the try block, and on a worker thread that killed the whole process.
        if (url.isBlank()) return null
        val connection = try {
            java.net.URL(url).openConnection() as java.net.HttpURLConnection
        } catch (error: Exception) {
            android.util.Log.w("CctvRepository", "frame failed for $url: ${error.message}")
            return null
        }
        return try {
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("User-Agent", frameUserAgent(url))
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
