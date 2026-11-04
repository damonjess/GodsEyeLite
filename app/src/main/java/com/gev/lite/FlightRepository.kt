package com.gev.lite

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/** One aircraft as sent to the globe. Units are already SI-ish for Cesium. */
data class Flight(
    val hex: String,
    val callsign: String,
    val registration: String,
    val type: String,
    val lat: Double,
    val lon: Double,
    /** Height above the WGS84 ellipsoid in metres (what Cesium positions use). */
    val altitudeM: Double,
    val speedMs: Double,
    val trackDeg: Double,
    /** Climb (+) or descent (-) in metres per second. */
    val climbMs: Double,
    val military: Boolean,
)

/**
 * Fetches live aircraft around a point from the community ADS-B aggregators.
 *
 * Same idea as [CctvRepository]: native HTTP has no CORS, so Kotlin does the
 * fetching and the WebView only draws. Both endpoints are keyless and share the
 * readsb-style `/v2/point/{lat}/{lon}/{radius_nm}` shape; the second is a
 * fallback if the first is down or rate-limiting.
 *
 * Be a good citizen: these are free shared services. The globe polls roughly
 * every 10 seconds and never more often than every few seconds.
 *
 * It also looks up, on demand and only for the plane you tap, an aircraft photo
 * (Planespotters.net public API) and the route for its callsign (adsb.lol).
 * Results are cached so tapping the same plane again costs nothing.
 */
internal class FlightRepository {

    companion object {
        private const val TAG = "FlightRepository"
        private const val TIMEOUT_MS = 8_000
        private const val MAX_RADIUS_NM = 400
        private const val MIN_RADIUS_NM = 5
        private const val MAX_FLIGHTS = 1200

        private const val FT_TO_M = 0.3048
        private const val KT_TO_MS = 0.514444
        private const val FPM_TO_MS = 0.00508

        private const val ADSB_LOL_URL = "https://api.adsb.lol/v2/point/%s/%s/%d"

        private const val PHOTO_URL = "https://api.planespotters.net/pub/photos/hex/%s"
        private val ROUTE_ENDPOINTS = listOf(
            "https://api.adsb.lol/api/0/routeset",
            "https://adsb.im/api/0/routeset",
        )
        private const val PHOTO_TTL_MS = 6 * 60 * 60 * 1000L
        private const val ROUTE_TTL_MS = 30 * 60 * 1000L
        private const val MAX_CACHE = 500

        // Airline-style callsigns only: three letters, a digit, then up to four more.
        private val AIRLINE_CALLSIGN = Regex("^[A-Z]{3}[0-9][A-Z0-9]{0,4}$")
    }

    /**
     * Planespotters rejects anonymous or library-default user agents, so this
     * must identify your app. Put a real contact (a URL or email) in here
     * before you ship.
     */
    private val userAgent = "GodsEyeLite/1.0 (Android; open ADS-B viewer; contact: dev@godseye-lite.app)"

    private class Cached(val atMs: Long, val value: JSONObject?)

    /** `value` null means "looked it up, there is nothing": still worth caching. */
    private class Loaded(val value: JSONObject?)

    private val photoCache = ConcurrentHashMap<String, Cached>()
    private val routeCache = ConcurrentHashMap<String, Cached>()

    /** Returns the cached value, or runs [load]. A null from [load] (network failure) is not cached. */
    private fun lookup(
        cache: ConcurrentHashMap<String, Cached>,
        key: String,
        ttlMs: Long,
        load: () -> Loaded?,
    ): JSONObject? {
        val now = System.currentTimeMillis()
        cache[key]?.let { if (now - it.atMs < ttlMs) return it.value }
        val loaded = load() ?: return null
        if (cache.size > MAX_CACHE) cache.clear()
        cache[key] = Cached(now, loaded.value)
        return loaded.value
    }

    /** Returns the aircraft near the point, or null if every endpoint failed. */
    fun fetchAround(lat: Double, lon: Double, radiusNm: Int): List<Flight>? {
        if (!lat.isFinite() || !lon.isFinite()) return null
        val radius = radiusNm.coerceIn(MIN_RADIUS_NM, MAX_RADIUS_NM)
        val latText = String.format(Locale.US, "%.4f", lat)
        val lonText = String.format(Locale.US, "%.4f", lon)

        // Primary: adsb.lol (retry up to 2 times)
        val adsbUrl = String.format(Locale.US, ADSB_LOL_URL, latText, lonText, radius)
        for (attempt in 1..2) {
            val body = httpGet(adsbUrl) ?: continue
            val parsed = runCatching { parse(body) }
                .onFailure { android.util.Log.w(TAG, "parse failed for $adsbUrl: ${it.message}") }
                .getOrNull()
            if (!parsed.isNullOrEmpty()) return parsed
        }

        // Fallback: OpenSky Network bounding box
        val deltaLat = radius / 60.0
        val cosLat = Math.cos(Math.toRadians(lat)).coerceAtLeast(0.1)
        val deltaLon = radius / (60.0 * cosLat)
        val lamin = String.format(Locale.US, "%.4f", lat - deltaLat)
        val lamax = String.format(Locale.US, "%.4f", lat + deltaLat)
        val lomin = String.format(Locale.US, "%.4f", lon - deltaLon)
        val lomax = String.format(Locale.US, "%.4f", lon + deltaLon)
        val openSkyUrl = "https://opensky-network.org/api/states/all?lamin=$lamin&lamax=$lamax&lomin=$lomin&lomax=$lomax"

        val openSkyBody = httpGet(openSkyUrl) ?: return null
        return runCatching { parseOpenSky(openSkyBody) }
            .onFailure { android.util.Log.w(TAG, "parseOpenSky failed for $openSkyUrl: ${it.message}") }
            .getOrNull()
    }

    /**
     * Parses an OpenSky Network response.
     */
    internal fun parseOpenSky(body: String): List<Flight> {
        val root = JSONObject(body)
        val states = root.optJSONArray("states") ?: return emptyList()
        val out = ArrayList<Flight>(minOf(states.length(), MAX_FLIGHTS))

        for (i in 0 until states.length()) {
            if (out.size >= MAX_FLIGHTS) break
            val state = states.optJSONArray(i) ?: continue
            if (state.length() < 12) continue

            val hex = state.optString(0, "").trim().lowercase(Locale.US)
            if (hex.isEmpty()) continue

            val lon = state.optDouble(5, Double.NaN)
            val lat = state.optDouble(6, Double.NaN)
            if (lat.isNaN() || lon.isNaN()) continue

            val onGround = state.optBoolean(8, false)
            if (onGround) continue

            val altBaro = state.optDouble(7, Double.NaN)
            val altGeo = state.optDouble(13, Double.NaN)
            val altitudeM = when {
                !altGeo.isNaN() && altGeo >= 0 -> altGeo
                !altBaro.isNaN() && altBaro >= 0 -> altBaro
                else -> continue
            }

            val speedMs = state.optDouble(9, 0.0).takeIf { !it.isNaN() } ?: 0.0
            val trackDeg = state.optDouble(10, 0.0).takeIf { !it.isNaN() } ?: 0.0
            val climbMs = state.optDouble(11, 0.0).takeIf { !it.isNaN() } ?: 0.0

            out += Flight(
                hex = hex,
                callsign = state.optString(1, "").trim(),
                registration = "",
                type = "",
                lat = lat,
                lon = lon,
                altitudeM = altitudeM,
                speedMs = speedMs,
                trackDeg = trackDeg,
                climbMs = climbMs,
                military = false,
            )
        }
        return out
    }

    /**
     * Parses a readsb v2 response.
     *
     * Details that matter, because they quietly break a naive parser:
     *  - `alt_baro` is a number of feet OR the string "ground". Grounded
     *    aircraft are skipped: they would pile up on runways and aprons.
     *  - `alt_geom` (GNSS height, feet, above the ellipsoid) is preferred when
     *    present because it is the same datum Cesium positions use; barometric
     *    altitude is the fallback.
     *  - `gs` is knots, `baro_rate` is feet per minute, `track` is degrees.
     *  - Military aircraft carry bit 0 of `dbFlags`.
     */
    internal fun parse(body: String): List<Flight> {
        val root = JSONObject(body)
        val array = root.optJSONArray("ac") ?: root.optJSONArray("aircraft") ?: return emptyList()
        val out = ArrayList<Flight>(minOf(array.length(), MAX_FLIGHTS))

        for (i in 0 until array.length()) {
            if (out.size >= MAX_FLIGHTS) break
            val ac = array.optJSONObject(i) ?: continue
            if (!ac.has("lat") || !ac.has("lon")) continue

            val baro = ac.opt("alt_baro")
            if (baro is String) continue // "ground"

            val geom = ac.optDouble("alt_geom", Double.NaN)
            val altitudeFt = when {
                !geom.isNaN() -> geom
                baro is Number -> baro.toDouble()
                else -> continue
            }

            val lat = ac.optDouble("lat", Double.NaN)
            val lon = ac.optDouble("lon", Double.NaN)
            if (lat.isNaN() || lon.isNaN()) continue

            val hex = ac.optString("hex", "").trim().lowercase(Locale.US)
            if (hex.isEmpty()) continue

            val speedKt = ac.optDouble("gs", Double.NaN)
            val track = ac.optDouble("track", Double.NaN)
            // Without a heading and speed there is nothing to dead-reckon with.
            val hasMotion = !speedKt.isNaN() && !track.isNaN()

            out += Flight(
                hex = hex,
                callsign = ac.optString("flight", "").trim(),
                registration = ac.optString("r", "").trim(),
                type = ac.optString("t", "").trim(),
                lat = lat,
                lon = lon,
                altitudeM = (altitudeFt * FT_TO_M).coerceAtLeast(0.0),
                speedMs = if (hasMotion) speedKt * KT_TO_MS else 0.0,
                trackDeg = if (hasMotion) track else 0.0,
                climbMs = ac.optDouble("baro_rate", 0.0).takeIf { !it.isNaN() }?.times(FPM_TO_MS) ?: 0.0,
                military = (ac.optInt("dbFlags", 0) and 1) != 0,
            )
        }
        return out
    }

    // ── On-demand details for a tapped plane ────────────────────────────────

    /**
     * Latest photo for an aircraft, or null. Returns {src, by, link}.
     *
     * Planespotters' terms: show the photographer's name next to the photo,
     * link the photo to [link], and load the image in the viewer (the globe's
     * <img>), never download or re-host it. This returns only the metadata.
     */
    fun photoFor(hex: String): JSONObject? =
        lookup(photoCache, hex.lowercase(Locale.US), PHOTO_TTL_MS) {
            val url = String.format(Locale.US, PHOTO_URL, hex.uppercase(Locale.US))
            val body = httpRequest(url) ?: return@lookup null
            val first = runCatching { JSONObject(body).optJSONArray("photos")?.optJSONObject(0) }.getOrNull()
            val src = first?.optJSONObject("thumbnail_large")?.optString("src").orEmpty()
            val link = first?.optString("link").orEmpty()
            if (first == null || !src.startsWith("https://") || !link.startsWith("https://")) {
                Loaded(null)
            } else {
                Loaded(
                    JSONObject()
                        .put("src", src)
                        .put("by", first.optString("photographer", ""))
                        .put("link", link),
                )
            }
        }

    /**
     * Route for an airline callsign, or null. Returns {from:{code,city,lat,lon}, to:{...}}.
     * Uses the lat/lon of the plane so the service can reject a route that does
     * not make sense for where the aircraft actually is.
     */
    fun routeFor(callsign: String, lat: Double, lon: Double): JSONObject? {
        val cs = callsign.trim().uppercase(Locale.US)
        if (!AIRLINE_CALLSIGN.matches(cs)) return null
        return lookup(routeCache, cs, ROUTE_TTL_MS) {
            val payload = JSONObject().put(
                "planes",
                JSONArray().put(JSONObject().put("callsign", cs).put("lat", lat).put("lng", lon)),
            ).toString()
            for (url in ROUTE_ENDPOINTS) {
                val body = httpRequest(url, "POST", payload) ?: continue
                return@lookup Loaded(runCatching { parseRoute(body) }.getOrNull())
            }
            null
        }
    }

    internal fun parseRoute(body: String): JSONObject? {
        val trimmed = body.trim()
        val entry = if (trimmed.startsWith("[")) JSONArray(trimmed).optJSONObject(0) else JSONObject(trimmed)
        if (entry == null) return null

        // Some responses say whether the route fits the plane's position.
        val plausible = entry.opt("plausible")
        if ((plausible is Boolean && !plausible) || (plausible is Number && plausible.toInt() == 0)) return null

        val codes = entry.optString("airport_codes", "")
        if (codes.isBlank() || codes.equals("unknown", ignoreCase = true)) return null
        val parts = codes.split("-").filter { it.isNotBlank() }
        if (parts.size < 2) return null

        val airports = entry.optJSONArray("_airports")
        val from = airportJson(airports?.optJSONObject(0), parts.first()) ?: return null
        val to = airportJson(airports?.optJSONObject((airports?.length() ?: 0) - 1), parts.last()) ?: return null
        return JSONObject().put("from", from).put("to", to)
    }

    private fun airportJson(airport: JSONObject?, fallbackCode: String): JSONObject? {
        val code = airport?.optString("iata").orEmpty().ifBlank { airport?.optString("icao").orEmpty() }
            .ifBlank { fallbackCode }
        if (code.isBlank()) return null
        val out = JSONObject().put("code", code).put("city", airport?.optString("location").orEmpty())
        val lat = airport?.optDouble("lat", Double.NaN) ?: Double.NaN
        val lon = airport?.optDouble("lon", Double.NaN) ?: Double.NaN
        if (!lat.isNaN() && !lon.isNaN()) out.put("lat", lat).put("lon", lon)
        return out
    }

    /**
     * Compact JSON for the globe. Short keys on purpose: a busy region is
     * several hundred aircraft, pushed every few seconds.
     *
     * h hex, c callsign, r registration, t type, la/lo degrees, a altitude m,
     * s speed m/s, k track deg, v climb m/s, m military.
     */
    fun toJson(flights: List<Flight>): JSONArray {
        val array = JSONArray()
        for (f in flights) {
            array.put(
                JSONObject()
                    .put("h", f.hex)
                    .put("c", f.callsign)
                    .put("r", f.registration)
                    .put("t", f.type)
                    .put("la", f.lat)
                    .put("lo", f.lon)
                    .put("a", Math.round(f.altitudeM))
                    .put("s", Math.round(f.speedMs * 10) / 10.0)
                    .put("k", Math.round(f.trackDeg))
                    .put("v", Math.round(f.climbMs * 10) / 10.0)
                    .put("m", f.military),
            )
        }
        return array
    }

    private fun httpGet(url: String): String? = httpRequest(url)

    private fun httpRequest(url: String, method: String = "GET", body: String? = null): String? {
        val connection = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        return try {
            connection.requestMethod = method
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("User-Agent", userAgent)
            connection.setRequestProperty("Accept", "application/json")
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            if (connection.responseCode !in 200..299) null
            else connection.inputStream.bufferedReader().use { it.readText() }
        } catch (error: Exception) {
            android.util.Log.w(TAG, "$method failed for $url: ${error.message}")
            null
        } finally {
            connection.disconnect()
        }
    }
}
