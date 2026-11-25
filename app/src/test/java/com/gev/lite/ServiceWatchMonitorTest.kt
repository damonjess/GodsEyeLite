package com.gev.lite

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ServiceWatchMonitorTest {

    private val config = ServiceWatchConfig(lat = 51.5074, lon = -0.1278, radiusKm = 25.0)

    private fun flight(
        hex: String,
        reason: String,
        lat: Double = config.lat,
        lon: Double = config.lon,
    ) = Flight(
        hex = hex,
        callsign = "TEST$hex",
        registration = "",
        type = "EC45",
        lat = lat,
        lon = lon,
        altitudeM = 500.0,
        speedMs = 40.0,
        trackDeg = 90.0,
        climbMs = 0.0,
        military = false,
        emergency = reason.isNotEmpty(),
        emergencyReason = reason,
    )

    @Test
    fun firstPollSeedsWithoutAlertingAndSortsNearestFirst() {
        val monitor = ServiceWatchMonitor()
        val near = flight("aaa111", "MEDICAL", lat = config.lat + 0.02)
        val far = flight("bbb222", "POLICE", lat = config.lat + 0.10)

        val first = monitor.update(listOf(far, near), config)

        assertEquals(listOf("aaa111", "bbb222"), first.nearby.map { it.hex })
        assertTrue(first.newAircraft.isEmpty())
    }

    @Test
    fun onlyNewAircraftTriggerAlerts() {
        val monitor = ServiceWatchMonitor()
        val existing = flight("aaa111", "MEDICAL")
        monitor.update(listOf(existing), config)

        val newcomer = flight("ccc333", "RESCUE", lat = config.lat + 0.03)
        val second = monitor.update(listOf(existing, newcomer), config)

        assertEquals(listOf("ccc333"), second.newAircraft.map { it.hex })

        val third = monitor.update(listOf(existing, newcomer), config)
        assertTrue(third.newAircraft.isEmpty())
    }

    @Test
    fun normalAndDistantAircraftAreIgnored() {
        val monitor = ServiceWatchMonitor()
        val normal = flight("aaa111", "")
        val distant = flight("bbb222", "FIRE", lat = config.lat + 0.30)
        val inRange = flight("ccc333", "FIRE", lat = config.lat + 0.05)

        val update = monitor.update(listOf(normal, distant, inRange), config)

        assertEquals(listOf("ccc333"), update.nearby.map { it.hex })
        assertTrue(update.newAircraft.isEmpty())
    }

    @Test
    fun alertRoleFilterOnlyMatchesSelectedRoles() {
        val monitor = ServiceWatchMonitor()
        val medical = flight("aaa111", "MEDICAL", lat = config.lat + 0.02)
        val police = flight("bbb222", "POLICE", lat = config.lat + 0.04)
        val fire = flight("ccc333", "FIRE", lat = config.lat + 0.06)

        val update = monitor.update(
            listOf(medical, police, fire),
            config.copy(roles = setOf(ServiceWatchRole.POLICE)),
        )

        assertEquals(listOf("bbb222"), update.nearby.map { it.hex })
        assertTrue(update.newAircraft.isEmpty())
    }

    @Test
    fun resetSeedsAgainForAChangedWatchArea() {
        val monitor = ServiceWatchMonitor()
        val aircraft = flight("aaa111", "MEDICAL")
        monitor.update(listOf(aircraft), config)
        monitor.reset()

        val update = monitor.update(listOf(aircraft), config)

        assertTrue(update.newAircraft.isEmpty())
        assertEquals(1, update.nearby.size)
    }

    @Test
    fun distanceBearingAndLabelsAreStable() {
        val distance = ServiceWatchMonitor.distanceM(
            config.lat,
            config.lon,
            config.lat + 0.05,
            config.lon,
        )
        assertTrue(distance in 5_000.0..6_000.0)
        assertEquals("N", ServiceWatchMonitor.bearing(51.0, 0.0, 52.0, 0.0))
        assertEquals("MEDEVAC", ServiceWatchMonitor.serviceLabel("medical"))
        assertEquals("POLICE", ServiceWatchMonitor.serviceLabel("POLICE"))
        assertEquals("EMERGENCY", ServiceWatchMonitor.serviceLabel(""))
        assertEquals(ServiceWatchRole.MEDEVAC, ServiceWatchRole.ofReason("MEDICAL"))
        assertEquals(ServiceWatchRole.POLICE, ServiceWatchRole.ofReason("police"))
        assertEquals(ServiceWatchRole.FIRE, ServiceWatchRole.ofReason("FIRE"))
        assertEquals(ServiceWatchRole.RESCUE, ServiceWatchRole.ofReason("RESCUE"))
        assertEquals(ServiceWatchRole.OTHER, ServiceWatchRole.ofReason("7700"))
        assertEquals(
            setOf(ServiceWatchRole.POLICE, ServiceWatchRole.FIRE),
            ServiceWatchRole.parseCsv("POLICE,FIRE,unknown"),
        )
    }
}
