package com.gev.lite

import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

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
    /** ADS-B emitter category, when supplied (A7 is rotorcraft). */
    val category: String = "",
    /** Emergency or emergency-service flight, from ADS-B status or a known callsign. */
    val emergency: Boolean = false,
    /** Short reason shown next to an emergency badge (for example 7700 or MEDICAL). */
    val emergencyReason: String = "",
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
        private const val ADSBDB_URL = "https://api.adsbdb.com/v0/aircraft/%s"
        private const val SERVICE_TIMEOUT_MS = 2_500
        private const val SERVICE_TTL_MS = 24 * 60 * 60 * 1000L
        private const val SERVICE_EMPTY_TTL_MS = 5 * 60 * 1000L
        private const val MAX_SERVICE_LOOKUPS = 24

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

        // Military aircraft usually carry the dbFlags military bit. These
        // strong callsign prefixes catch aircraft whose feed omits that bit.
        private val MILITARY_CALLSIGN = Regex(
            "^(RCH|REACH|RRR|ASCOT|CFC|NATO|SAM|SPAR|IRON|TITAN|JAKE|MAGIC|NIGHT|DEATH|VENUS|HOMER|TOPCAT)\\d",
        )

        // Callsigns used by air-ambulance, police, fire and rescue services.
        // Keep these specific: a registration-style callsign has no service
        // information and is enriched from its registration owner below.
        private val MEDICAL_CALLSIGN = Regex(
            "^(LIFEGUARD|MEDEVAC|AIRMED|HELIMED|HEMS|AIRAMB|NHZ|MEDSTAR|AIRLIFE|LIFEFLIGHT|LIFESTAR|LIFETEAM|LIFEMED|MEDFLIGHT|AIRCARE|CAREFLIGHT|CALSTAR|PHI\\d|REACH\\d|DUSTOFF|PEDRO|JOLLY)",
        )
        private val POLICE_CALLSIGN = Regex(
            "^(POLICE|NPAS|SHERIFF|TROOPER|HIGHWAY|PUBLIC\\s*SAFETY|AIRSUPPORT)",
        )
        private val FIRE_CALLSIGN = Regex("^(CALFIRE|FIREHAWK|HELITACK)")
        private val RESCUE_CALLSIGN = Regex("^(COASTGUARD|COAST\\s*GUARD|RESCUE)")

        // Owners use different words from callsigns. These are matched against
        // registration metadata for helicopters, where a registration-style
        // callsign is common.
        private val MEDICAL_OWNER = Regex(
            "(AIR\\s+AMBULANCE|AMBULANCE|MEDEVAC|MEDSTAR|MEDFLIGHT|" +
                "EMERGENCY\\s+MEDICINE|MEDICAL\\s+(CENTER|EMERGENCY|SERVICES|TRANSPORT)|" +
                "LIFEGUARD|LIFEFLIGHT|LIFESTAR|LIFETEAM|LIFEMED|LIFELINE|AIRCARE|CAREFLIGHT|CALSTAR|" +
                "PHI\\s+AIR|REACH\\s+AIR|HELIMED|HEMS|AIR\\s+MEDICAL)",
            RegexOption.IGNORE_CASE,
        )
        private val POLICE_OWNER = Regex(
            "(POLICE|SHERIFF|PUBLIC\\s+SAFETY|HIGHWAY\\s+PATROL|STATE\\s+TROOPER|TROOPER|" +
                "LAW\\s+ENFORCEMENT|GENDARMERIE|CARABINIERI|POLIZEI|POLICIA|POLÍCIA)",
            RegexOption.IGNORE_CASE,
        )
        private val FIRE_OWNER = Regex(
            "(FIRE\\s+DEPARTMENT|FIRE\\s+RESCUE|CAL\\s*FIRE|FOREST\\s+SERVICE|FIREHAWK|" +
                "FIRE\\s+HAWK|HELITACK)",
            RegexOption.IGNORE_CASE,
        )
        private val RESCUE_OWNER = Regex(
            "(COAST\\s*GUARD|SEARCH\\s+(AND|&)\\s+RESCUE|MOUNTAIN\\s+RESCUE|RESCUE\\s+SERVICE)",
            RegexOption.IGNORE_CASE,
        )

        // Only used to decide which registration records are worth looking up.
        private val HELICOPTER_TYPES = setOf(
            "A109", "A119", "A139", "A169", "A189", "AS32", "AS50", "AS55", "AS65",
            "AW109", "AW139", "AW169", "AW189", "B06", "B407", "B429", "EC45",
            "H135", "H145", "H160", "H500", "H64", "R22", "R44", "UH60", "V22",
        )
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

    private class CachedService(val atMs: Long, val reason: String)

    private val photoCache = ConcurrentHashMap<String, Cached>()
    private val routeCache = ConcurrentHashMap<String, Cached>()
    private val serviceCache = ConcurrentHashMap<String, CachedService>()

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

            val callsign = ac.optString("flight", "").trim()
            val military = (ac.optInt("dbFlags", 0) and 1) != 0 ||
                MILITARY_CALLSIGN.containsMatchIn(callsign.uppercase(Locale.US))
            val emergencyReason = emergencyReasonOf(
                callsign,
                ac.optString("emergency", ""),
                ac.optString("squawk", ""),
            )

            val speedKt = ac.optDouble("gs", Double.NaN)
            val track = ac.optDouble("track", Double.NaN)
            // Without a heading and speed there is nothing to dead-reckon with.
            val hasMotion = !speedKt.isNaN() && !track.isNaN()

            out += Flight(
                hex = hex,
                callsign = callsign,
                registration = ac.optString("r", "").trim(),
                type = ac.optString("t", "").trim(),
                lat = lat,
                lon = lon,
                altitudeM = (altitudeFt * FT_TO_M).coerceAtLeast(0.0),
                speedMs = if (hasMotion) speedKt * KT_TO_MS else 0.0,
                trackDeg = if (hasMotion) track else 0.0,
                climbMs = ac.optDouble("baro_rate", 0.0).takeIf { !it.isNaN() }?.times(FPM_TO_MS) ?: 0.0,
                military = military,
                category = ac.optString("category", "").trim().uppercase(Locale.US),
                emergency = emergencyReason.isNotEmpty(),
                emergencyReason = emergencyReason,
            )
        }
        return out
    }

    /**
     * Turns the ADS-B emergency status, squawk, or a known emergency-service
     * callsign into a short card label. A normal flight has an empty reason.
     */
    private fun emergencyReasonOf(callsign: String, emergency: String, squawk: String): String {
        val status = emergency.trim().lowercase(Locale.US)
        if (status.isNotEmpty() && status != "none" && status != "no") {
            return when (status) {
                "general" -> "7700"
                "lifeguard" -> "MEDICAL"
                "minfuel" -> "MIN FUEL"
                "nordo" -> "RADIO"
                "unlawful" -> "UNLAWFUL"
                "downed" -> "DOWNED"
                else -> status.uppercase(Locale.US)
            }
        }

        when (squawk.trim()) {
            "7500" -> return "HIJACK"
            "7600" -> return "RADIO"
            "7700" -> return "7700"
        }

        return serviceCallsignReason(callsign)
    }

    private fun serviceCallsignReason(callsign: String): String {
        val cs = callsign.uppercase(Locale.US)
        return when {
            POLICE_CALLSIGN.containsMatchIn(cs) -> "POLICE"
            FIRE_CALLSIGN.containsMatchIn(cs) -> "FIRE"
            RESCUE_CALLSIGN.containsMatchIn(cs) -> "RESCUE"
            MEDICAL_CALLSIGN.containsMatchIn(cs) -> "MEDICAL"
            else -> ""
        }
    }

    /** Maps a registered owner/operator to a short service tag. */
    internal fun serviceOwnerReason(owner: String, flagCode: String = ""): String {
        val text = "$owner $flagCode"
        return when {
            POLICE_OWNER.containsMatchIn(text) -> "POLICE"
            MEDICAL_OWNER.containsMatchIn(text) -> "MEDICAL"
            FIRE_OWNER.containsMatchIn(text) -> "FIRE"
            RESCUE_OWNER.containsMatchIn(text) -> "RESCUE"
            else -> when (flagCode.trim().uppercase(Locale.US)) {
                "NHZ", "HEMS", "HELIMED", "MEDEVAC", "LIFEGUARD" -> "MEDICAL"
                "NPAS", "POLICE" -> "POLICE"
                else -> ""
            }
        }
    }

    private fun isHelicopter(flight: Flight): Boolean =
        flight.category.equals("A7", ignoreCase = true) ||
            HELICOPTER_TYPES.contains(flight.type.uppercase(Locale.US))

    /**
     * Adds service tags to helicopters whose ADS-B callsign is just a
     * registration. ADSBdb exposes the registered owner, which is where an air
     * ambulance, police or fire operator is usually named. This is deliberately
     * separate from [fetchAround]: the live aircraft are pushed first, then
     * this metadata update can arrive a moment later.
     */
    fun enrichEmergencyServices(flights: List<Flight>): List<Flight> {
        val candidates = flights.asSequence()
            .filter { !it.emergency && it.registration.isNotBlank() && isHelicopter(it) }
            .take(MAX_SERVICE_LOOKUPS)
            .toList()
        if (candidates.isEmpty()) return flights

        val executor = Executors.newFixedThreadPool(minOf(4, candidates.size))
        val reasons = try {
            val futures = candidates.associateBy { it.hex }.mapValues { (_, flight) ->
                executor.submit<String> { serviceReasonForRegistration(flight.registration) }
            }
            futures.mapValues { (_, future) ->
                runCatching { future.get(SERVICE_TIMEOUT_MS + 1_000L, TimeUnit.MILLISECONDS) }
                    .getOrDefault("")
            }
        } finally {
            executor.shutdownNow()
        }

        var changed = false
        val enriched = flights.map { flight ->
            val reason = reasons[flight.hex].orEmpty()
            if (reason.isEmpty()) {
                flight
            } else {
                changed = true
                flight.copy(emergency = true, emergencyReason = reason)
            }
        }
        return if (changed) enriched else flights
    }

    private fun serviceReasonForRegistration(registration: String): String {
        val key = registration.uppercase(Locale.US)
        if (serviceCache.size > MAX_CACHE) serviceCache.clear()
        val now = System.currentTimeMillis()
        serviceCache[key]?.let { cached ->
            val ttl = if (cached.reason.isEmpty()) SERVICE_EMPTY_TTL_MS else SERVICE_TTL_MS
            if (now - cached.atMs < ttl) return cached.reason
        }

        val encoded = URLEncoder.encode(key, "UTF-8")
        val body = httpRequest(
            String.format(Locale.US, ADSBDB_URL, encoded),
            timeoutMs = SERVICE_TIMEOUT_MS,
        )
        if (body == null) {
            serviceCache[key] = CachedService(now, "")
            return ""
        }
        val aircraft = runCatching {
            JSONObject(body).optJSONObject("response")?.optJSONObject("aircraft")
        }.getOrNull()
        val reason = aircraft?.let {
            serviceOwnerReason(
                it.optString("registered_owner", ""),
                it.optString("registered_owner_operator_flag_code", ""),
            )
        }.orEmpty()
        serviceCache[key] = CachedService(now, reason)
        return reason
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
     * s speed m/s, k track deg, v climb m/s, m military, g ADS-B category.
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
                    .put("m", f.military)
                    .put("g", f.category)
                    .put("e", f.emergency)
                    .put("q", f.emergencyReason),
            )
        }
        return array
    }

    private fun httpGet(url: String): String? = httpRequest(url)

    private fun httpRequest(
        url: String,
        method: String = "GET",
        body: String? = null,
        timeoutMs: Int = TIMEOUT_MS,
    ): String? {
        val connection = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        return try {
            connection.requestMethod = method
            connection.connectTimeout = timeoutMs
            connection.readTimeout = timeoutMs
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
