package com.gev.lite

import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** The fixed area and role filters watched by one background Service Watch session. */
internal data class ServiceWatchConfig(
    val lat: Double,
    val lon: Double,
    val radiusKm: Double,
    val roles: Set<ServiceWatchRole> = ServiceWatchRole.all(),
)

internal enum class ServiceWatchRole(val wire: String) {
    MEDEVAC("MEDEVAC"),
    POLICE("POLICE"),
    FIRE("FIRE"),
    RESCUE("RESCUE"),
    OTHER("OTHER"),
    ;

    companion object {
        fun all(): Set<ServiceWatchRole> = values().toSet()

        fun fromWire(value: String): ServiceWatchRole? =
            values().firstOrNull { it.wire.equals(value.trim(), ignoreCase = true) }

        fun parseCsv(value: String?): Set<ServiceWatchRole> = value
            .orEmpty()
            .split(',')
            .mapNotNull { fromWire(it) }
            .toSet()

        /** Maps the short reasons from FlightRepository to the alert filters. */
        fun ofReason(reason: String): ServiceWatchRole = when (reason.trim().uppercase()) {
            "MEDICAL" -> MEDEVAC
            "POLICE" -> POLICE
            "FIRE" -> FIRE
            "RESCUE" -> RESCUE
            else -> OTHER
        }
    }
}

internal data class ServiceWatchUpdate(
    /** Emergency/service aircraft currently inside the radius, nearest first. */
    val nearby: List<Flight>,
    /** Aircraft that were not present on an earlier successful poll. */
    val newAircraft: List<Flight>,
)

/**
 * Matches the WebView's Service Watch list and suppresses the burst of
 * notifications for aircraft that were already there when watching started.
 */
internal class ServiceWatchMonitor {

    private val known = HashSet<String>()
    private var seeded = false

    fun reset() {
        known.clear()
        seeded = false
    }

    fun update(flights: List<Flight>, config: ServiceWatchConfig): ServiceWatchUpdate {
        val radiusM = config.radiusKm * 1000.0
        val nearby = flights
            .asSequence()
            .filter { flight ->
                flight.emergency && ServiceWatchRole.ofReason(flight.emergencyReason) in config.roles
            }
            .map { flight -> flight to distanceM(config.lat, config.lon, flight.lat, flight.lon) }
            .filter { (_, distanceM) -> distanceM <= radiusM }
            .sortedBy { (_, distanceM) -> distanceM }
            .map { (flight, _) -> flight }
            .toList()

        if (!seeded) {
            seeded = true
            known.addAll(nearby.map { it.hex })
            return ServiceWatchUpdate(nearby, emptyList())
        }

        return ServiceWatchUpdate(nearby, nearby.filter { known.add(it.hex) })
    }

    companion object {
        private const val EARTH_R = 6_371_000.0
        private val COMPASS = arrayOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")

        /** Great-circle distance in metres. */
        fun distanceM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val rad = Math.PI / 180
            val a =
                sin(((lat2 - lat1) * rad) / 2).let { it * it } +
                    cos(lat1 * rad) * cos(lat2 * rad) *
                    sin(((lon2 - lon1) * rad) / 2).let { it * it }
            return 2 * EARTH_R * asin(sqrt(a).coerceAtMost(1.0))
        }

        /** Eight-point compass bearing, for the alert notification text. */
        fun bearing(lat1: Double, lon1: Double, lat2: Double, lon2: Double): String {
            val rad = Math.PI / 180
            val dLon = (lon2 - lon1) * rad
            val y = sin(dLon) * cos(lat2 * rad)
            val x =
                cos(lat1 * rad) * sin(lat2 * rad) -
                    sin(lat1 * rad) * cos(lat2 * rad) * cos(dLon)
            val degrees = (atan2(y, x) / rad + 360) % 360
            return COMPASS[Math.round(degrees / 45).toInt() % 8]
        }

        fun serviceLabel(reason: String): String {
            val normalized = reason.trim().uppercase()
            return when (normalized) {
                "MEDICAL" -> "MEDEVAC"
                "POLICE", "FIRE", "RESCUE" -> normalized
                else -> normalized.ifEmpty { "EMERGENCY" }
            }
        }
    }
}
